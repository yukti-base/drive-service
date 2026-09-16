package org.yuktisetu.driveservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.db.OutboxEvent;
import org.yuktisetu.model.OutboxStatus;
import org.yuktisetu.repository.OutboxEventRepository;

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
    private static final int MAX_ATTEMPTS = 10;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxRelay(OutboxEventRepository outboxEventRepository,
                       KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelayString = "${yuktisetu.outbox.relay.fixed-delay-ms:2000}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = outboxEventRepository.claimBatch(BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }
        for (OutboxEvent event : batch) {
            try {
                kafkaTemplate.send(event.getTopic(), event.getPartitionKey(), event.getPayload())
                        .get();
                event.setStatus(OutboxStatus.PUBLISHED);
                event.setPublishedAt(Instant.now());
                event.setLastError(null);
            } catch (Exception e) {
                int attempts = event.getAttempts() + 1;
                event.setAttempts(attempts);
                event.setLastError(e.getClass().getSimpleName() + ": " + e.getMessage());
                if (attempts >= MAX_ATTEMPTS) {
                    event.setStatus(OutboxStatus.FAILED);
                    log.error("Outbox event {} FAILED permanently after {} attempts, topic={}",
                            event.getEventId(), attempts, event.getTopic());
                } else {
                    // 2^attempts seconds, capped by the attempt ceiling above.
                    event.setAvailableAt(Instant.now().plusSeconds((long) Math.pow(2, attempts)));
                    log.warn("Outbox event {} publish failed (attempt {}), retrying: {}",
                            event.getEventId(), attempts, e.getMessage());
                }
            }
        }
        outboxEventRepository.saveAll(batch);
    }
}
