package org.yuktisetu.driveservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.drive.db.DriveScoreRun;
import org.yuktisetu.drive.db.JobPosting;
import org.yuktisetu.drive.model.ActorKind;
import org.yuktisetu.drive.model.ApplicationStatus;
import org.yuktisetu.drive.model.PostingStatus;
import org.yuktisetu.drive.repository.DriveScoreRunRepository;
import org.yuktisetu.drive.repository.DriveScoringGateRepository;
import org.yuktisetu.drive.repository.JobApplicationRepository;
import org.yuktisetu.drive.repository.JobPostingRepository;
import org.yuktisetu.driveservice.dto.ReopenDriveRequest;
import org.yuktisetu.driveservice.exception.DriveExceptions;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Extending the deadline of a sealed drive: applications reopen, and the drive's
 * ranking and shortlist are discarded so they can be worked out again for the
 * larger pool (decided 2026-10-03 and 2026-10-27).
 *
 * In one transaction, under the posting's write lock -- the same lock the seal
 * and the scoring consumers take, so none of them can interleave:
 *   1. the scoring gate is removed, so nothing can rank the drive while it is
 *      open again; the next seal opens a fresh one;
 *   2. the active score run is SUPERSEDED, never deleted -- it is the record of
 *      what students were told;
 *   3. every application in the pool (IN_REVIEW, SHORTLISTED, REJECTED) goes back
 *      to APPLIED, unsealed, with a history row carrying the TnP's reason;
 *   4. the posting is PUBLISHED again with the new deadline, its shortlist no
 *      longer published;
 *   5. students are told, in one batched notice per audience.
 *
 * Kept, by decision: coding captures (only new applicants are scraped) and the
 * TnP's coding-review decisions. The ordinary deadline sweep seals the drive
 * again at the new deadline, and the gate, ranking and shortlist follow as for
 * any drive. A drive with offers cannot be reopened.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DriveReopenService {

    /** A deadline the sweep would seal on its very next tick is not a reopen. */
    private static final Duration MIN_WINDOW = Duration.ofMinutes(5);
    private static final List<ApplicationStatus> POOL =
            List.of(ApplicationStatus.IN_REVIEW, ApplicationStatus.SHORTLISTED, ApplicationStatus.REJECTED);

    private final JobPostingRepository postingRepository;
    private final JobApplicationRepository applicationRepository;
    private final DriveScoringGateRepository gateRepository;
    private final DriveScoreRunRepository runRepository;
    private final ShortlistService shortlistService;
    private final DriveNotificationService notificationService;
    private final NamedParameterJdbcTemplate jdbc;

    @Transactional
    public ReopenDriveRequest.Result reopen(UserPrincipal actor, Long postingId, ReopenDriveRequest req) {
        // The posting lock first: the seal and the scoring consumer take it too.
        JobPosting posting = postingRepository.findForSealing(postingId)
                .orElseThrow(() -> new DriveExceptions.DriveNotVisibleException(postingId));
        shortlistService.authoriseWholeDrive(actor, postingId);

        if (posting.getStatus() == PostingStatus.PUBLISHED) {
            throw new DriveExceptions.ScoringStateException("STILL_OPEN",
                    "Applications are still open. Change the deadline in the drive's Details instead.");
        }
        if (posting.getStatus() != PostingStatus.APPLICATIONS_CLOSED) {
            throw new DriveExceptions.ScoringStateException("CANNOT_REOPEN",
                    "A drive that is " + posting.getStatus() + " cannot be reopened.");
        }
        Instant now = Instant.now();
        Instant deadline = req.applicationDeadline();
        if (deadline == null || deadline.isBefore(now.plus(MIN_WINDOW))) {
            throw new DriveExceptions.InvalidRequestException(
                    "The new deadline must be at least " + MIN_WINDOW.toMinutes() + " minutes from now.");
        }
        String reason = req.reason() == null ? "" : req.reason().trim();
        if (reason.isEmpty()) {
            throw new DriveExceptions.InvalidRequestException("Give a reason for reopening this drive.");
        }
        long offers = applicationRepository.countByPostingAndStatusIn(postingId, EnumSet.of(
                ApplicationStatus.OFFERED, ApplicationStatus.OFFER_ACCEPTED, ApplicationStatus.OFFER_DECLINED));
        if (offers > 0) {
            throw new DriveExceptions.ScoringStateException("OFFERS_MADE",
                    "Offers have been made on this drive, so it cannot be reopened.");
        }

        // 1. The gate. Locked before it is removed, the same order every gate path uses.
        gateRepository.lockByPostingId(postingId).ifPresent(g -> {
            gateRepository.delete(g);
            gateRepository.flush();
        });

        // 2. The active run, superseded.
        DriveScoreRun run = runRepository.findByPostingIdAndSupersededAtIsNull(postingId).orElse(null);
        if (run != null) {
            run.setSupersededAt(now);
            run.setSupersededBy(actor.userId());
            runRepository.saveAndFlush(run);
        }

        // 3. The pool, back to APPLIED. History first, then the update, per status.
        String historyReason = "Drive reopened: " + reason;
        List<Long> previouslyShortlisted = new ArrayList<>();
        List<Long> applicantUsers = new ArrayList<>();
        int reopened = 0;
        for (ApplicationStatus from : POOL) {
            List<Long> ids = applicationRepository.findIdsByPostingAndStatus(postingId, from);
            if (ids.isEmpty()) {
                continue;
            }
            List<Long> users = studentsOf(ids);
            (from == ApplicationStatus.SHORTLISTED ? previouslyShortlisted : applicantUsers).addAll(users);
            applicationRepository.recordHistoryForIds(ids, from.name(), ApplicationStatus.APPLIED.name(),
                    now, actor.userId(), ActorKind.TNP.name(), historyReason);
            reopened += applicationRepository.reopenForIds(ids, from, actor.userId());
        }
        // Applied but never sealed (withdrew and re-applied, or applied in the last
        // seconds) are applicants too, and are told the same as the rest.
        applicantUsers.addAll(studentsOf(applicationRepository.findIdsByPostingAndStatus(postingId, ApplicationStatus.APPLIED))
                .stream().filter(u -> !previouslyShortlisted.contains(u) && !applicantUsers.contains(u)).toList());

        // 4. The posting. The bulk updates cleared the persistence context; re-read.
        posting = postingRepository.findById(postingId).orElseThrow();
        posting.setStatus(PostingStatus.PUBLISHED);
        posting.setApplicationDeadline(deadline);
        posting.setApplicationsClosedAt(null);
        posting.setShortlistPublishedAt(null);
        posting.setShortlistPublishedBy(null);
        posting.setReopenedAt(now);
        posting.setReopenedBy(actor.userId());
        posting.setReopenCount(posting.getReopenCount() + 1);
        postingRepository.save(posting);

        // 5. Notices: one batch per audience.
        List<Long> eligibleNotApplied = jdbc.queryForList("""
                SELECT e.student_user_id
                  FROM posting_eligible_students e
                 WHERE e.posting_id = :postingId
                   AND e.is_deleted = false
                   AND NOT EXISTS (SELECT 1 FROM job_applications a
                                    WHERE a.posting_id = e.posting_id
                                      AND a.student_user_id = e.student_user_id
                                      AND a.is_deleted = false
                                      AND a.status <> 'WITHDRAWN')
                """, new MapSqlParameterSource("postingId", postingId), Long.class);
        notificationService.queueDriveReopened(postingId, deadline,
                previouslyShortlisted, applicantUsers, eligibleNotApplied);

        log.info("Drive reopened: postingId={} actorId={} newDeadline={} reopenCount={} applicationsReopened={} "
                        + "previouslyShortlisted={} supersededRunId={} eligibleNotified={} reason={}",
                postingId, actor.userId(), deadline, posting.getReopenCount(), reopened,
                previouslyShortlisted.size(), run == null ? null : run.getId(), eligibleNotApplied.size(), reason);

        return new ReopenDriveRequest.Result(postingId, deadline, posting.getReopenCount(), reopened,
                previouslyShortlisted.size(), run == null ? null : run.getId(), eligibleNotApplied.size());
    }

    private List<Long> studentsOf(List<Long> applicationIds) {
        if (applicationIds.isEmpty()) {
            return List.of();
        }
        return jdbc.queryForList("SELECT student_user_id FROM job_applications WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", applicationIds), Long.class);
    }
}
