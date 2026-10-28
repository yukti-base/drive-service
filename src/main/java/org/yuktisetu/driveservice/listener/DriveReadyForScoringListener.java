package org.yuktisetu.driveservice.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.yuktisetu.driveservice.service.ScoringRunService;
import org.yuktisetu.messaging.events.DriveReadyForScoringEvent;
import org.yuktisetu.messaging.events.DriveTopics;
import org.yuktisetu.messaging.events.EventEnvelope;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Starts the scoring engine for a drive whose gate has just become READY.
 *
 * Same failure handling as CodingScrapeCompletedListener: an unreadable message
 * is logged and skipped; anything else is rethrown so the container retries it.
 * If it still fails, nothing is lost -- the reconciler scores every READY drive
 * that has no run, straight from the gate table.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "yuktisetu.kafka.enabled", havingValue = "true")
public class DriveReadyForScoringListener {

    private final ScoringRunService scoringRunService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = DriveTopics.DRIVE_READY_FOR_SCORING,
            groupId = "drive-service.scoring",
            concurrency = "1")
    public void onReady(String message) {
        EventEnvelope<DriveReadyForScoringEvent> envelope;
        try {
            envelope = objectMapper.readValue(
                    message, new TypeReference<EventEnvelope<DriveReadyForScoringEvent>>() {});
        } catch (JacksonException e) {
            log.error("Skipping unreadable ready-for-scoring message: {} -- {}", e.getOriginalMessage(),
                    message.length() > 500 ? message.substring(0, 500) + "..." : message);
            return;
        }
        if (envelope.payload() == null || envelope.payload().postingId() == null) {
            log.error("Skipping ready-for-scoring eventId={} with no postingId", envelope.eventId());
            return;
        }
        log.debug("Ready for scoring received: eventId={} postingId={} pool={}",
                envelope.eventId(), envelope.payload().postingId(), envelope.payload().poolSize());
        scoringRunService.run(envelope.eventId(), envelope.payload().postingId());
    }
}
