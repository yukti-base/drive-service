package org.yuktisetu.driveservice.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.yuktisetu.driveservice.service.ScoringGateService;
import org.yuktisetu.messaging.events.CodingScrapeCompletedEvent;
import org.yuktisetu.messaging.events.DriveTopics;
import org.yuktisetu.messaging.events.EventEnvelope;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Settles application captures when the scraper finishes a job.
 *
 * The handler is milliseconds of database work and never calls out of the
 * process, so one consumer thread keeps up with any deadline burst this
 * platform can produce, and it cannot be starved by a slow platform -- the
 * scraping happened elsewhere, long before this message existed.
 *
 * Failure handling, in two kinds:
 *  - A message that cannot be parsed will never parse. It is logged and
 *    skipped rather than blocking the partition.
 *  - Anything else (database briefly unavailable, gate lock timeout) is thrown,
 *    so the container's error handler retries it with backoff. If it still
 *    fails, the offset moves on and the reconciler settles the capture from the
 *    job table within one pass. Nothing is lost either way, because the job's
 *    final state in the database -- not this message -- is the source of truth.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "yuktisetu.kafka.enabled", havingValue = "true")
public class CodingScrapeCompletedListener {

    private final ScoringGateService scoringGateService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = DriveTopics.CODING_SCRAPE_COMPLETED,
            groupId = "drive-service.coding-capture",
            concurrency = "1")
    public void onCompleted(String message) {
        EventEnvelope<CodingScrapeCompletedEvent> envelope;
        try {
            envelope = objectMapper.readValue(
                    message, new TypeReference<EventEnvelope<CodingScrapeCompletedEvent>>() {});
        } catch (JacksonException e) {
            log.error("Skipping unreadable scrape-completion message: {} -- {}", e.getOriginalMessage(),
                    message.length() > 500 ? message.substring(0, 500) + "..." : message);
            return;
        }
        if (envelope.payload() == null || envelope.payload().jobId() == null) {
            log.error("Skipping scrape-completion eventId={} with no jobId", envelope.eventId());
            return;
        }
        log.debug("Scrape completion received: eventId={} jobId={} outcome={}",
                envelope.eventId(), envelope.payload().jobId(), envelope.payload().outcome());
        scoringGateService.onScrapeCompleted(envelope.eventId(), envelope.payload());
    }
}
