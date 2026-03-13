package com.koostack.cveil.plugin.event.jooq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.koostack.cveil.core.event.models.AggregateSnapshotTrigger;
import com.koostack.cveil.core.event.models.PlatformEvent;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.models.TenantContext;
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

    /** Starts the snapshot worker with the given number of worker threads. */
    public void start(int workerCount) {
        if (!running.compareAndSet(false, true)) {
            log.warn("SnapshotWorker is already running");
            return;
        }
        workers =
                Executors.newFixedThreadPool(
                        workerCount,
                        r -> {
                            Thread t = new Thread(r, "snapshot-worker");
                            t.setDaemon(true);
                            return t;
                        });
        pollerThread = new Thread(this::pollLoop, "snapshot-poller");
        pollerThread.setDaemon(true);
        pollerThread.start();
        log.info("SnapshotWorker started with {} workers", workerCount);
    }

    /** Gracefully shuts down the worker, waiting up to 5 seconds for in-flight work to complete. */
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

                        JsonNode baseState;
                        List<PlatformEvent> events;
                        if (existing.isPresent()) {
                            // Start from existing snapshot, replay only the delta
                            baseState = objectMapper.readTree(existing.get().snapshot());
                            events =
                                    eventRepository.fetchChangesAfterVersion(
                                            key.aggregateType(),
                                            key.aggregateId().toString(),
                                            existing.get().version());
                        } else {
                            baseState = objectMapper.createObjectNode();
                            events =
                                    eventRepository.fetchChangesUpToVersion(
                                            key.aggregateType(),
                                            key.aggregateId().toString(),
                                            trigger.targetVersion());
                        }

                        JsonNode state = replayPayloads(baseState, events);

                        String serializedSnapshot = objectMapper.writeValueAsString(state);
                        eventRepository.insertSnapshot(
                                key.aggregateType(),
                                key.aggregateId().toString(),
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

    private JsonNode replayPayloads(JsonNode baseState, List<PlatformEvent> events) {
        ObjectNode state =
                baseState.isObject()
                        ? (ObjectNode) baseState.deepCopy()
                        : objectMapper.createObjectNode();
        for (PlatformEvent event : events) {
            if (event.payload() != null) {
                try {
                    JsonNode payloadNode = objectMapper.readTree(event.payload());
                    if (payloadNode.isObject()) {
                        payloadNode
                                .fields()
                                .forEachRemaining(
                                        field -> state.set(field.getKey(), field.getValue()));
                    }
                } catch (Exception e) {
                    throw new RuntimeException(
                            "Failed to replay payload for event "
                                    + event.eventId()
                                    + " ("
                                    + event.eventType()
                                    + ")",
                            e);
                }
            }
        }
        return state;
    }
}
