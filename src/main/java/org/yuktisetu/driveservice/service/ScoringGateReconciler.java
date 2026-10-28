package org.yuktisetu.driveservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The safety net under the event-driven scoring gate. NOT how drives normally
 * become ready -- that happens the instant the last condition settles.
 *
 * It exists for the cases an event cannot cover:
 *   - a CodingScrapeCompleted message lost or skipped after exhausting retries
 *     (the job table still says the job finished, and that is what this reads);
 *   - Kafka switched off, which would otherwise leave every drive waiting;
 *   - a change made somewhere with no hook into the gate yet, such as the TnP
 *     disqualifying a flagged student from admin-service.
 *
 * Cheap by construction: it looks only at gates not yet READY (a partial index),
 * which outside a deadline evening is none. Each drive is settled in its own
 * transaction under the gate lock, so it can run alongside the consumer and on
 * several instances at once without double-counting anything.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "yuktisetu.drive.scoring-gate.reconcile.enabled", havingValue = "true",
        matchIfMissing = true)
public class ScoringGateReconciler {

    private final ScoringGateService scoringGateService;
    private final ScoringRunService scoringRunService;

    @Scheduled(fixedDelayString = "${yuktisetu.drive.scoring-gate.reconcile.fixed-delay-ms:300000}",
            initialDelayString = "${yuktisetu.drive.scoring-gate.reconcile.initial-delay-ms:60000}")
    public void reconcileOpenGates() {
        scoreUnscoredReadyDrives();

        List<Long> open;
        try {
            open = scoringGateService.openGatePostingIds();
        } catch (Exception e) {
            log.error("Scoring-gate reconcile could not list open gates, skipping this pass: {}", e.getMessage(), e);
            return;
        }
        if (open.isEmpty()) {
            return;
        }
        log.debug("Scoring-gate reconcile: {} open gate(s): {}", open.size(), open);
        for (Long postingId : open) {
            try {
                scoringGateService.reconcile(postingId);
            } catch (Exception e) {
                // One drive's problem must not stop the rest being checked.
                log.error("Scoring-gate reconcile failed for postingId={}: {}", postingId, e.getMessage(), e);
            }
        }
    }

    /**
     * A READY drive with no scoring run means its DriveReadyForScoring event was
     * lost, or ended FAILED in the outbox. Score it from the gate row, so a lost
     * message only delays scoring by one pass.
     */
    private void scoreUnscoredReadyDrives() {
        List<Object[]> due;
        try {
            due = scoringRunService.readyWithoutRun();
        } catch (Exception e) {
            log.error("Scoring reconcile could not list unscored READY drives: {}", e.getMessage(), e);
            return;
        }
        for (Object[] r : due) {
            Long postingId = ((Number) r[0]).longValue();
            String eventId = (String) r[1];
            try {
                log.warn("Scoring postingId={} from the reconciler: READY with no run (eventId={})", postingId, eventId);
                scoringRunService.run(eventId, postingId);
            } catch (Exception e) {
                log.error("Scoring reconcile failed for postingId={}: {}", postingId, e.getMessage(), e);
            }
        }
    }
}
