package org.yuktisetu.driveservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yuktisetu.db.OutboxEvent;
import org.yuktisetu.events.EventEnvelope;
import org.yuktisetu.repository.OutboxEventRepository;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * Queues an event for Kafka by writing it to the outbox INSIDE the caller's
 * transaction.
 *
 * Same contract as admin-service's copy: the business change and the event commit
 * together or not at all. For this service the case that matters is the deadline
 * sweep -- sealing the applicant pool and telling the world the pool is sealed
 * must not be separable, or the shortlisting engine never learns there is work
 * to do and the drive silently stalls with recruiters already on campus.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxWriter {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public <T> String write(String topic,
                            String eventType,
                            String aggregateType,
                            Object aggregateId,
                            String partitionKey,
                            Long actorUserId,
                            T payload) {

        String eventId = UUID.randomUUID().toString();
        EventEnvelope<T> envelope = new EventEnvelope<>(
                eventId, eventType, "v1", Instant.now(), actorUserId, payload);

        OutboxEvent row = new OutboxEvent();
        row.setEventId(eventId);
        row.setTopic(topic);
        row.setEventType(eventType);
        row.setAggregateType(aggregateType);
        row.setAggregateId(aggregateId == null ? null : String.valueOf(aggregateId));
        row.setPartitionKey(partitionKey);
        row.setPayload(objectMapper.writeValueAsString(envelope));
        row.setAvailableAt(Instant.now());

        outboxEventRepository.save(row);
        log.info("Outbox event queued: eventId={} type={} topic={} aggregate={}:{}",
                eventId, eventType, topic, aggregateType, aggregateId);
        return eventId;
    }
}
