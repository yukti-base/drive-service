package org.yuktisetu.driveservice.recovery;

import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.core.recovery.StartupRecovery;
import org.yuktisetu.messaging.repository.OutboxEventRepository;

/**
 * Puts back in the queue every outbox event a previous run gave up on while
 * Kafka was unreachable, or left mid-publish when it stopped. The relay then
 * sends them in order. admin-service runs the same step; the update is
 * idempotent, so whichever starts first does the work.
 */
@Component
@Order(10)
@RequiredArgsConstructor
public class OutboxStartupRecovery implements StartupRecovery {

    private final OutboxEventRepository outboxEventRepository;

    @Override
    public String name() {
        return "outbox";
    }

    @Override
    @Transactional
    public int recover() {
        return outboxEventRepository.requeueRecoverable();
    }
}
