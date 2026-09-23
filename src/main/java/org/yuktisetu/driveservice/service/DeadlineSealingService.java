package org.yuktisetu.driveservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.drive.db.JobPosting;
import org.yuktisetu.messaging.events.ApplicantPoolSealedEvent;
import org.yuktisetu.messaging.events.DriveTopics;
import org.yuktisetu.drive.model.ApplicationStatus;
import org.yuktisetu.drive.model.PostingStatus;
import org.yuktisetu.drive.model.PostingStatusTransitions;
import org.yuktisetu.drive.repository.JobApplicationRepository;
import org.yuktisetu.drive.repository.JobPostingRepository;
import org.yuktisetu.drive.repository.PostingEligibleStudentRepository;
import org.yuktisetu.core.exception.ApiException;

import java.time.Instant;
import java.util.List;

/**
 * Sealing the applicant pool at the deadline -- the handover point to the
 * shortlisting engine, which is not built yet.
 *
 * Deliberately split from the scheduler that calls it, so each posting is sealed
 * in its OWN transaction (REQUIRES_NEW). One malformed drive must not roll back
 * the sealing of every other drive that expired in the same minute.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeadlineSealingService {

    private final JobPostingRepository postingRepository;
    private final JobApplicationRepository applicationRepository;
    private final PostingEligibleStudentRepository eligibleStudentRepository;
    private final OutboxWriter outboxWriter;

    /** Ids of postings whose deadline has passed while still PUBLISHED. */
    @Transactional(readOnly = true)
    public List<Long> findDue() {
        try {
            return postingRepository.findIdsDueForSealing(PostingStatus.PUBLISHED, Instant.now());
        } catch (Exception e) {
            // Returning empty rather than propagating: a failed scan must not kill
            // the scheduler thread, and the next tick a minute later retries. The
            // apply endpoint enforces the deadline independently, so a missed sweep
            // delays the status flip without ever letting a late application in.
            log.error("Deadline scan failed, skipping this tick: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Seal one posting.
     *
     * The write lock here is the other half of the race that ApplicationService's
     * shared read lock closes: an apply reading PUBLISHED a millisecond before the
     * deadline must either commit before this transaction starts, or block and then
     * fail its own status re-check. Without the pair, an application can commit
     * AFTER the bulk update and stay at APPLIED forever -- outside the sealed pool,
     * invisible to the scoring engine, with nothing in the logs.
     *
     * The status flip, the bulk application update and the outbox row are one
     * transaction. If the event were published after the commit instead, a failed
     * publish would leave a sealed pool that nothing downstream ever hears about,
     * and the drive would silently stall with recruiters already scheduled.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean seal(Long postingId) {
        try {
            return doSeal(postingId);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            // Rethrown so this posting's transaction rolls back cleanly -- a
            // half-sealed pool is worse than an unsealed one. The scheduler logs it
            // and moves to the next drive, and the next tick retries this one.
            log.error("Sealing failed for postingId={}: {}", postingId, e.getMessage(), e);
            throw e;
        }
    }

    private boolean doSeal(Long postingId) {
        JobPosting posting = postingRepository.findForSealing(postingId).orElse(null);
        if (posting == null) {
            return false;
        }
        // Re-checked under the lock: another instance may have sealed this posting
        // between findDue() and here.
        if (posting.getStatus() != PostingStatus.PUBLISHED) {
            return false;
        }
        if (posting.getApplicationDeadline().isAfter(Instant.now())) {
            return false;
        }
        if (!PostingStatusTransitions.canTransition(posting.getStatus(), PostingStatus.APPLICATIONS_CLOSED)) {
            return false;
        }

        Instant now = Instant.now();
        posting.setStatus(PostingStatus.APPLICATIONS_CLOSED);
        posting.setApplicationsClosedAt(now);
        postingRepository.save(posting);

        // One statement, not one entity per applicant: a 2000-applicant drive must
        // not become 2000 entity loads while this transaction holds a write lock on
        // the posting row.
        int sealed = applicationRepository.sealPool(
                postingId, ApplicationStatus.APPLIED, ApplicationStatus.IN_REVIEW, now);

        long eligibleCount = eligibleStudentRepository.countByPosting_IdAndIsDeletedFalse(postingId);

        outboxWriter.write(
                DriveTopics.APPLICANT_POOL_SEALED,
                "ApplicantPoolSealed",
                "JobPosting",
                postingId,
                String.valueOf(postingId),
                null,
                new ApplicantPoolSealedEvent(
                        postingId, now, sealed, (int) eligibleCount,
                        posting.getShortlistingMode().name()));

        log.info("Applicant pool sealed: postingId={} applicants={} eligible={} mode={}",
                postingId, sealed, eligibleCount, posting.getShortlistingMode());
        return true;
    }
}
