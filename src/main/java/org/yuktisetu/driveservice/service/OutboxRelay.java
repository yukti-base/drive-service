package org.yuktisetu.driveservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.messaging.db.OutboxEvent;
import org.yuktisetu.messaging.model.OutboxStatus;
import org.yuktisetu.messaging.outbox.OutboxRetryPolicy;
import org.yuktisetu.messaging.repository.OutboxEventRepository;

import java.time.Instant;
import java.util.List;

/**
 * Drains the outbox to Kafka.
 *
 * The rows are written inside business transactions, which is what makes the
 * publish atomic with the change it describes. This relay is the other half:
 * it claims due rows with FOR UPDATE SKIP LOCKED, so several instances can drain
 * the queue in parallel without two of them fighting over the same head-of-queue
 * row.
 *
 * Failures back off exponentially and land in FAILED after ten attempts, which is
 * the state worth alerting on -- a FAILED row means a drive event that never
 * reached anyone.
 *
 * Gated with the rest of the Kafka wiring. While it is off, rows accumulate as
 * PENDING and are published once a broker exists; nothing is lost.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "yuktisetu.kafka.enabled", havingValue = "true")
public class OutboxRelay {

    private static final int BATCH_SIZE = 200;

    /** One send may wait this long for the broker before it counts as failed. */
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxRelay(OutboxEventRepository outboxEventRepository,
                       KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Retries for as long as Kafka is down (OutboxRetryPolicy, 2026-11-01): a row
     * that cannot be sent stays PENDING with a growing backoff, capped at five
     * minutes, and survives restarts. Before this, ten failures (about 17
     * minutes) marked it FAILED and nothing ever sent it, so a longer outage
     * silently lost shortlist and round notices.
     *
     * The first broker failure in a batch stops the batch: the rest would only
     * wait out the same timeout one by one, holding their row locks.
     */
    @Scheduled(fixedDelayString = "${yuktisetu.outbox.relay.fixed-delay-ms:2000}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = outboxEventRepository.claimBatch(BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }
        List<OutboxEvent> touched = new java.util.ArrayList<>(batch.size());
        for (OutboxEvent event : batch) {
            touched.add(event);
            try {
                kafkaTemplate.send(event.getTopic(), event.getPartitionKey(), event.getPayload())
                        .get(SEND_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
                event.setStatus(OutboxStatus.PUBLISHED);
                event.setPublishedAt(Instant.now());
                event.setLastError(null);
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                int attempts = event.getAttempts() + 1;
                event.setAttempts(attempts);
                String error = e.getClass().getSimpleName() + ": " + e.getMessage();
                if (!OutboxRetryPolicy.isRetryable(e)) {
                    event.setStatus(OutboxStatus.FAILED);
                    event.setLastError(OutboxRetryPolicy.NON_RETRYABLE_PREFIX + error);
                    log.error("Outbox event {} rejected by Kafka and will not be retried, topic={}: {}",
                            event.getEventId(), event.getTopic(), error);
                    continue;
                }
                event.setLastError(error);
                event.setAvailableAt(Instant.now().plus(OutboxRetryPolicy.backoff(attempts)));
                if (OutboxRetryPolicy.worthAnError(attempts)) {
                    log.error("Outbox event {} still unpublished after {} attempts (Kafka unreachable?), "
                                    + "retrying in {} s, topic={}: {}", event.getEventId(), attempts,
                            OutboxRetryPolicy.backoff(attempts).toSeconds(), event.getTopic(), error);
                } else {
                    log.warn("Outbox event {} publish failed (attempt {}), retrying in {} s: {}",
                            event.getEventId(), attempts, OutboxRetryPolicy.backoff(attempts).toSeconds(), error);
                }
                // The broker is likely down: leave the rest of the batch untouched
                // for the next pass instead of timing out on each of them.
                break;
            }
        }
        outboxEventRepository.saveAll(touched);
    }

    /** Every five minutes: say so loudly if anything has waited more than five minutes. */
    @Scheduled(fixedDelayString = "${yuktisetu.outbox.backlog-check-ms:300000}", initialDelay = 60000)
    public void reportBacklog() {
        try {
            Object[] row = outboxEventRepository.backlogOlderThan(300).get(0);
            long count = ((Number) row[0]).longValue();
            if (count > 0) {
                log.error("Outbox backlog: {} event(s) waiting more than 5 minutes, oldest from {}. "
                        + "They are retried automatically; check that Kafka is up.", count, row[1]);
            }
        } catch (Exception e) {
            log.warn("Could not check the outbox backlog: {}", e.getMessage());
        }
    }
}
