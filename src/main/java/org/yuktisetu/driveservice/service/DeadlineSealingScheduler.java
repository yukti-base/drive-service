package org.yuktisetu.driveservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Closes drives whose deadline has passed, once a minute.
 *
 * Property-gated and OFF by default. This job mutates live drives in a database
 * every developer shares, so it must be switched on deliberately for the
 * environment that should own it rather than running on whoever's laptop happens
 * to be up. Turning it off does not weaken the deadline: the apply endpoint
 * enforces the window against the server clock independently, so an un-swept
 * posting still refuses applications. The sweep exists to move the status and
 * fire the event, not to be the gate.
 *
 * A Redis lock keeps two instances from sealing the same drive concurrently. It
 * is belt-and-braces rather than the real defence -- the pessimistic write lock
 * plus the status re-check inside DeadlineSealingService already make a double
 * seal harmless -- but it saves the wasted work and the duplicate outbox row.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "yuktisetu.drive.sealing.enabled", havingValue = "true")
public class DeadlineSealingScheduler {

    private static final String LOCK_KEY = "drive:sealing:lock";

    private final DeadlineSealingService sealingService;
    private final StringRedisTemplate redis;
    private final Duration lockTtl;

    public DeadlineSealingScheduler(DeadlineSealingService sealingService,
                                    StringRedisTemplate redis,
                                    @Value("${yuktisetu.drive.sealing.fixed-delay-ms:60000}") long fixedDelayMs) {
        this.sealingService = sealingService;
        this.redis = redis;
        // Slightly under the interval, so a crashed run releases the lock before
        // the next tick rather than wedging the sweep until someone notices.
        this.lockTtl = Duration.ofMillis(Math.max(5_000, fixedDelayMs - 5_000));
    }

    @Scheduled(fixedDelayString = "${yuktisetu.drive.sealing.fixed-delay-ms:60000}")
    public void sweep() {
        Boolean acquired = redis.opsForValue().setIfAbsent(LOCK_KEY, "1", lockTtl);
        if (!Boolean.TRUE.equals(acquired)) {
            return;
        }
        try {
            List<Long> due = sealingService.findDue();
            if (due.isEmpty()) {
                return;
            }
            int sealed = 0;
            for (Long postingId : due) {
                try {
                    if (sealingService.seal(postingId)) {
                        sealed++;
                    }
                } catch (RuntimeException e) {
                    // Its own transaction, so this failure is contained. Log and
                    // carry on -- one bad drive must not stop the others closing.
                    log.error("Failed to seal postingId={}: {}", postingId, e.getMessage(), e);
                }
            }
            log.info("Deadline sweep: {} posting(s) due, {} sealed", due.size(), sealed);
        } finally {
            redis.delete(LOCK_KEY);
        }
    }
}
