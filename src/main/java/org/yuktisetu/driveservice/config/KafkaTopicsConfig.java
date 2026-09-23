package org.yuktisetu.driveservice.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.yuktisetu.messaging.events.DriveTopics;

/**
 * Topics this service PRODUCES, declared explicitly so partition counts are a
 * decision rather than whatever auto-creation would guess -- the broker runs with
 * auto.create.topics.enable=false for that reason.
 *
 * drive-service consumes nothing. Eligibility resolution used to be driven by a
 * posting.published consumer here; it now runs in admin-service directly after
 * publish, which is both where administrative work belongs and one less thing that
 * silently does not happen when no broker is up.
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
}
