package org.yuktisetu.driveservice.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import org.yuktisetu.messaging.events.DriveTopics;

/**
 * Topics this service PRODUCES, declared explicitly so partition counts are a
 * decision rather than whatever auto-creation would guess -- the broker runs with
 * auto.create.topics.enable=false for that reason.
 *
 * drive-service consumes exactly one topic: coding scrape completions, which
 * settle application captures. Eligibility resolution used to be driven by a
 * posting.published consumer here; it now runs in admin-service directly after
 * publish.
 *
 * Gated with the rest of the Kafka wiring -- without a broker these beans would
 * just retry connecting and fill the log.
 */
@Configuration
@ConditionalOnProperty(name = "yuktisetu.kafka.enabled", havingValue = "true")
public class KafkaTopicsConfig {

    /**
     * Keyed by postingId so everything about one drive stays ordered. Three
     * partitions is ample: a placement season is hundreds of drives, not thousands
     * of events per second.
     */
    @Bean
    public NewTopic applicantPoolSealedTopic() {
        return TopicBuilder.name(DriveTopics.APPLICANT_POOL_SEALED)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic applicantPoolSealedDlq() {
        return TopicBuilder.name(DriveTopics.dlqFor(DriveTopics.APPLICANT_POOL_SEALED))
                .partitions(1)
                .replicas(1)
                .build();
    }

    /**
     * Scrape completions. Produced by yuktisetu-scraper through the shared outbox
     * and published by this service's relay, so declared here. Keyed by student.
     */
    @Bean
    public NewTopic codingScrapeCompletedTopic() {
        return TopicBuilder.name(DriveTopics.CODING_SCRAPE_COMPLETED)
                .partitions(3)
                .replicas(1)
                .build();
    }

    /** The scoring engine's trigger, written once per drive. Keyed by posting. */
    @Bean
    public NewTopic driveReadyForScoringTopic() {
        return TopicBuilder.name(DriveTopics.DRIVE_READY_FOR_SCORING)
                .partitions(3)
                .replicas(1)
                .build();
    }

    /**
     * Retry a failed message four times, two seconds apart, then move on.
     *
     * Bounded on purpose: an unbounded retry would hold the partition behind one
     * bad message, which is a stall by another name. Moving on loses nothing,
     * because the reconciler settles any capture whose job has finished, straight
     * from the job table.
     */
    @Bean
    public CommonErrorHandler kafkaErrorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(2000L, 4L));
    }
}
