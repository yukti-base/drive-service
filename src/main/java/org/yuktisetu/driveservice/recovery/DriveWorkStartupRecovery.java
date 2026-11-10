package org.yuktisetu.driveservice.recovery;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.yuktisetu.core.recovery.StartupRecovery;
import org.yuktisetu.driveservice.service.DeadlineSealingScheduler;
import org.yuktisetu.driveservice.service.ScoringGateReconciler;

/**
 * The drive pipeline's periodic jobs, run once before the first request after a
 * restart instead of waiting for their next tick:
 *
 *  - the deadline sweep, so a drive whose deadline passed while the service was
 *    down is sealed before anyone can act on it as if it were open;
 *  - the scoring-gate reconciler, which settles captures whose scrapes finished
 *    while we were down and scores READY drives that have no run.
 *
 * Both are idempotent and are exactly what the schedulers do every few minutes;
 * running them first only removes the wait. Each is optional -- off in profiles
 * that disable the scheduler -- and the step reports how many drives were open.
 */
@Slf4j
@Component
@Order(20)
public class DriveWorkStartupRecovery implements StartupRecovery {

    private final ObjectProvider<DeadlineSealingScheduler> sealing;
    private final ObjectProvider<ScoringGateReconciler> gates;
    private final JdbcTemplate jdbc;

    public DriveWorkStartupRecovery(ObjectProvider<DeadlineSealingScheduler> sealing,
                                    ObjectProvider<ScoringGateReconciler> gates,
                                    JdbcTemplate jdbc) {
        this.sealing = sealing;
        this.gates = gates;
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "drive deadlines and scoring gates";
    }

    @Override
    public int recover() {
        Integer overdue = jdbc.queryForObject("""
                SELECT count(*) FROM job_postings
                 WHERE status = 'PUBLISHED' AND is_deleted = false AND application_deadline < now()
                """, Integer.class);
        Integer openGates = jdbc.queryForObject("""
                SELECT count(*) FROM drive_scoring_gates g
                 WHERE g.state = 'COLLECTING'
                    OR (g.state = 'READY' AND NOT EXISTS (
                        SELECT 1 FROM drive_score_runs r WHERE r.posting_id = g.posting_id AND r.superseded_at IS NULL))
                """, Integer.class);
        sealing.ifAvailable(DeadlineSealingScheduler::sweep);
        gates.ifAvailable(ScoringGateReconciler::reconcileOpenGates);
        return (overdue == null ? 0 : overdue) + (openGates == null ? 0 : openGates);
    }
}
