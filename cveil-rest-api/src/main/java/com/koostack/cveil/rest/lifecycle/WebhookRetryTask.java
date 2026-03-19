package com.koostack.cveil.rest.lifecycle;

import com.koostack.cveil.platform.webhook.WebhookDeliveryService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scheduled job that retries failed webhook deliveries. Runs every 30 seconds and picks up
 * delivery-log entries whose {@code next_retry_at} timestamp has passed. Each entry is retried once
 * per cycle; on failure it is re-scheduled with exponential backoff until {@link
 * WebhookDeliveryService#MAX_RETRIES} is exhausted.
 */
@ApplicationScoped
public class WebhookRetryTask {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookRetryTask.class);

    @Inject
    @Named("webhookDeliveryService")
    WebhookDeliveryService webhookDeliveryService;

    @Scheduled(every = "30s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void retryFailedDeliveries() {
        if (webhookDeliveryService == null) {
            return;
        }
        try {
            int processed = webhookDeliveryService.retryPendingDeliveries();
            if (processed > 0) {
                LOG.info("WebhookRetryTask: retried {} pending deliveries", processed);
            }
        } catch (Exception e) {
            LOG.warn("WebhookRetryTask: retry cycle failed", e);
        }
    }
}
