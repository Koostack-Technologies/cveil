package com.koostack.operio.tenant.event.jooq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.koostack.operio.core.event.models.AggregateSnapshotTrigger;
import com.koostack.operio.core.event.models.TenantEvent;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.TenantContext;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Consumes {@link AggregateSnapshotTrigger} messages from a shared queue and dispatches them to an
 * internal worker pool for snapshot processing.
 */
public class SnapshotWorker {

    private static final Logger log = LoggerFactory.getLogger(SnapshotWorker.class);
    private static final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    private final LinkedBlockingQueue<AggregateSnapshotTrigger> queue;
    private final EventRepository eventRepository;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Thread pollerThread;
    private ExecutorService workers;

    public SnapshotWorker(
            LinkedBlockingQueue<AggregateSnapshotTrigger> queue, EventRepository eventRepository) {
        this.queue = queue;
        this.eventRepository = eventRepository;
    }

    public void start(int workerCount) {
        if (!running.compareAndSet(false, true)) {
            log.warn("SnapshotWorker is already running");
            return;
        }
        workers =
                Executors.newFixedThreadPool(
                        workerCount,
                        r -> {
                            Thread t = new Thread(r, "tenant-snapshot-worker");
                            t.setDaemon(true);
                            return t;
                        });
        pollerThread = new Thread(this::pollLoop, "tenant-snapshot-poller");
        pollerThread.setDaemon(true);
        pollerThread.start();
        log.info("SnapshotWorker started with {} workers", workerCount);
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        pollerThread.interrupt();
        workers.shutdown();
        try {
            workers.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("SnapshotWorker stopped");
    }

    private void pollLoop() {
        while (running.get()) {
            try {
                AggregateSnapshotTrigger trigger = queue.take();
                workers.submit(() -> processSnapshot(trigger));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void processSnapshot(AggregateSnapshotTrigger trigger) {
        AggregateKey key = trigger.aggregateKey();
        TenantContext.runWhere(
                key.tenantId(),
                () -> {
                    try {
                        var existing =
                                eventRepository.fetchLatestSnapshot(
                                        key.aggregateType(), key.aggregateId().toString());
                        if (existing.isPresent()
                                && existing.get().version() >= trigger.targetVersion()) {
                            log.debug(
                                    "Snapshot already exists at version {} for {}/{}, skipping",
                                    existing.get().version(),
                                    key.aggregateType(),
                                    key.aggregateId());
                            return;
                        }

                        log.info(
                                "Processing snapshot for {}/{} at version {}",
                                key.aggregateType(),
                                key.aggregateId(),
                                trigger.targetVersion());

                        List<TenantEvent> events =
                                eventRepository.fetchChangesUpToVersion(
                                        key.aggregateType(),
                                        key.aggregateId().toString(),
                                        trigger.targetVersion());

                        JsonNode state = AggregateEventApplier.replayToJson(events);

                        String serializedSnapshot = objectMapper.writeValueAsString(state);
                        eventRepository.insertSnapshot(
                                key.aggregateType(),
                                key.aggregateId().toString(),
                                key.tenantId(),
                                trigger.targetVersion(),
                                serializedSnapshot);

                        log.info(
                                "Snapshot persisted for {}/{} at version {}",
                                key.aggregateType(),
                                key.aggregateId(),
                                trigger.targetVersion());
                    } catch (Exception e) {
                        log.warn(
                                "Snapshot processing failed for {}/{}",
                                key.aggregateType(),
                                key.aggregateId(),
                                e);
                    }
                });
    }
}
