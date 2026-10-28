package org.yuktisetu.driveservice.service;

import org.yuktisetu.drive.db.ApplicationCodingCapture;
import org.yuktisetu.drive.db.JobPosting;
import org.yuktisetu.drive.model.CaptureFlagReason;
import org.yuktisetu.drive.model.PostingStatus;

import java.time.Instant;
import java.util.Set;

/**
 * What a finished scrape means for one application's capture. Decided 2026-10-18.
 *
 *   captured                                         -> CAPTURED
 *   not the student's fault (platform down, blocked,
 *     our parser broke)                              -> TnP review, SERVER_ERROR
 *   the handle, first time, deadline still open      -> the student re-checks
 *   the handle, first time, deadline already passed  -> TnP review, AFTER_DEADLINE
 *   the handle, after the student's re-check         -> scored on their other platforms
 *
 * An outage is never quietly skipped: it hits every student on that platform at
 * once, so a whole drive could otherwise be scored without it unnoticed.
 *
 * Stateless, and the single place this table lives: the event consumer, the
 * reconciler and apply time all settle captures through it.
 */
final class CaptureOutcomePolicy {

    private CaptureOutcomePolicy() {}

    /** What the student is told about a settled capture. */
    enum Notice { NONE, CHECK_NEEDED, STILL_FAILING, FLAGGED }

    /** Outcomes the student can fix by correcting their username. */
    static final Set<String> HANDLE_PROBLEMS = Set.of("HANDLE_NOT_FOUND", CodingCaptureService.OUTCOME_NO_HANDLE);

    /** May the student still re-check for this drive? Open, and before its deadline. */
    static boolean recheckOpen(JobPosting posting, Instant now) {
        return posting != null
                && posting.getStatus() == PostingStatus.PUBLISHED
                && posting.getApplicationDeadline() != null
                && posting.getApplicationDeadline().isAfter(now);
    }

    static Notice settle(ApplicationCodingCapture c, JobPosting posting, boolean captured, Long snapshotId,
                         String outcome, String error, Instant now) {
        if (captured) {
            c.markCaptured(snapshotId, now);
            return Notice.NONE;
        }
        String o = outcome == null ? "UNKNOWN" : outcome;
        if (!HANDLE_PROBLEMS.contains(o)) {
            c.markFlagged(o, error, CaptureFlagReason.SERVER_ERROR, now);
            return Notice.FLAGGED;
        }
        if (c.getRecheckCount() > 0) {
            c.markFailedScoredOnOthers(o, error, now);
            return Notice.STILL_FAILING;
        }
        if (recheckOpen(posting, now)) {
            c.markAwaitingStudent(o, error, now);
            return Notice.CHECK_NEEDED;
        }
        c.markFlagged(o, error, CaptureFlagReason.AFTER_DEADLINE, now);
        return Notice.FLAGGED;
    }
}
