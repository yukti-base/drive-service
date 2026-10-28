package org.yuktisetu.driveservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.driveservice.dto.ApplicationResponse;
import org.yuktisetu.driveservice.exception.DriveExceptions;
import org.yuktisetu.drive.db.ApplicationStatusHistory;
import org.yuktisetu.drive.db.JobApplication;
import org.yuktisetu.drive.db.JobPosting;
import org.yuktisetu.drive.model.ActorKind;
import org.yuktisetu.drive.model.ApplicationSource;
import org.yuktisetu.drive.model.ApplicationStatus;
import org.yuktisetu.drive.model.ApplicationStatusTransitions;
import org.yuktisetu.drive.model.PostingStatusTransitions;
import org.yuktisetu.drive.model.ShortlistingMode;
import org.yuktisetu.drive.repository.ApplicationStatusHistoryRepository;
import org.yuktisetu.drive.repository.JobApplicationRepository;
import org.yuktisetu.drive.repository.JobPostingRepository;
import org.yuktisetu.drive.repository.PostingEligibleStudentRepository;
import org.yuktisetu.identity.repository.UserRepository;
import org.yuktisetu.profile.model.StudentDocumentType;
import org.yuktisetu.profile.repository.StudentDocumentRepository;
import org.yuktisetu.core.exception.ApiException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Easy Apply, and withdrawing.
 *
 * The student's placement data already exists, so applying carries no payload --
 * the whole operation is a permission decision plus one row.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApplicationService {

    private final JobPostingRepository postingRepository;
    private final JobApplicationRepository applicationRepository;
    private final ApplicationStatusHistoryRepository historyRepository;
    private final PostingEligibleStudentRepository eligibleStudentRepository;
    private final UserRepository userRepository;
    private final StudentDocumentRepository documentRepository;
    private final StudentEligibilityService eligibilityService;
    private final DriveNotificationService driveNotificationService;
    private final CodingCaptureService codingCaptureService;

    /**
     * Apply, in the order the checks have to happen.
     *
     *  1. Read the posting under a SHARED lock -- or an EXCLUSIVE one on an FCFS
     *     drive, see the lock choice below. This is what stops the deadline
     *     race: the sealing sweep takes an EXCLUSIVE lock on the same row, so this
     *     transaction either commits before sealing begins or blocks and then
     *     fails its own status check afterwards. Without it, an application landing
     *     a millisecond before the deadline can commit AFTER the pool was sealed
     *     and sit at APPLIED forever -- outside the pool, invisible to the
     *     shortlisting engine, and impossible to notice.
     *  2. Enforce the window against the SERVER clock. A disabled button is not
     *     enforcement.
     *  3. Require membership of the eligible set -- 404 if absent, so the id space
     *     cannot be walked.
     *  4. Re-evaluate eligibility LIVE. The index row may be days old; in between
     *     the student's CGPA could have changed, an offer could have landed, or the
     *     TnP could have edited the criteria.
     *  5. Respect the seat cap ONLY on a first-come-first-served drive. On a
     *     recruitment drive the seat count is a shortlist target applied after the
     *     deadline, so capping here would silently turn the drive into a race.
     *  6. Insert, or revive a withdrawn row. The unique constraint is the last
     *     line of defence against a double tap.
     */
    @Transactional
    public ApplicationResponse apply(Long postingId, Long studentUserId) {
        try {
            return doApply(postingId, studentUserId);
        } catch (ApiException e) {
            // Every refusal a student can legitimately hit -- window closed, not
            // eligible any more, already applied, cap reached -- is an ApiException
            // with a specific code, and must reach them unchanged.
            throw e;
        } catch (DataIntegrityViolationException e) {
            // Belt and braces: the inner method already catches this on its own
            // insert, but any other constraint tripping here must not surface as a
            // 500 to a student who simply tapped twice.
            log.info("Constraint violation applying to postingId={} studentUserId={}: {}",
                    postingId, studentUserId, e.getMessage());
            throw new DriveExceptions.AlreadyAppliedException();
        } catch (Exception e) {
            log.error("Unexpected failure applying to postingId={} studentUserId={}: {}",
                    postingId, studentUserId, e.getMessage(), e);
            throw new DriveExceptions.ApplicationFailedException();
        }
    }

    private ApplicationResponse doApply(Long postingId, Long studentUserId) {
        // Which lock to take has to be decided BEFORE the first locking read.
        // Taking the shared lock and upgrading it later would deadlock two
        // concurrent applies, each holding a share lock the other needs released.
        // So: one unlocked mode lookup, then the right lock, acquired once.
        ShortlistingMode mode = postingRepository.findShortlistingMode(postingId)
                .orElseThrow(() -> new DriveExceptions.DriveNotVisibleException(postingId));
        boolean capsAtApplyTime = mode.capsApplicationsAtApplyTime();

        JobPosting posting = (capsAtApplyTime
                ? postingRepository.findForSealing(postingId)   // exclusive: seats are a race
                : postingRepository.findForApply(postingId))    // shared: applies run in parallel
                .orElseThrow(() -> new DriveExceptions.DriveNotVisibleException(postingId));

        assertWindowOpen(posting);

        if (!eligibleStudentRepository.existsByPosting_IdAndStudent_IdAndIsDeletedFalse(postingId, studentUserId)) {
            log.debug("Apply refused: postingId={} not in eligible set for studentUserId={}", postingId, studentUserId);
            throw new DriveExceptions.DriveNotVisibleException(postingId);
        }

        List<String> failures = eligibilityService.check(posting, studentUserId);
        if (!failures.isEmpty()) {
            log.info("Apply refused on live re-check: postingId={} studentUserId={} failures={}",
                    postingId, studentUserId, failures);
            throw new DriveExceptions.EligibilityChangedException(failures);
        }

        // Seat cap, on FCFS drives only.
        //
        // Correct here precisely BECAUSE this branch holds the exclusive lock: the
        // count and the insert below are serialised per posting, so the cap cannot
        // be overshot. The same code under the shared lock would be a check-then-act
        // race where every concurrent applicant reads cap-1 and every one inserts.
        //
        // Not run at all on a recruitment drive: there, maxApplications is the
        // shortlist target and the shortlisting engine applies it after the seal.
        if (capsAtApplyTime && posting.getMaxApplications() != null) {
            long current = applicationRepository.countByPosting_IdAndIsDeletedFalseAndStatusNot(
                    postingId, ApplicationStatus.WITHDRAWN);
            if (current >= posting.getMaxApplications()) {
                log.info("Apply refused, FCFS seats full: postingId={} studentUserId={} cap={}",
                        postingId, studentUserId, posting.getMaxApplications());
                throw new DriveExceptions.ApplicationLimitReachedException(posting.getMaxApplications());
            }
        }

        Instant now = Instant.now();
        Optional<JobApplication> existing = applicationRepository
                .findByPosting_IdAndStudent_IdAndIsDeletedFalse(postingId, studentUserId);

        JobApplication application;
        ApplicationStatus from;

        if (existing.isPresent()) {
            application = existing.get();
            from = application.getStatus();
            if (from == ApplicationStatus.APPLIED) {
                throw new DriveExceptions.AlreadyAppliedException();
            }
            // Re-applying after a withdrawal revives THIS row rather than
            // inserting a second one, which is what keeps the unique constraint on
            // (posting, student) absolute instead of "absolute unless they changed
            // their mind".
            if (!ApplicationStatusTransitions.canTransition(from, ApplicationStatus.APPLIED)) {
                throw new DriveExceptions.ApplicationWindowClosedException(
                        "An application with status " + from + " cannot be reopened.");
            }
            application.setStatus(ApplicationStatus.APPLIED);
            application.setAppliedAt(now);
            application.setWithdrawnAt(null);
            application.setUpdatedBy(studentUserId);
            attachResume(application, studentUserId);
        } else {
            from = null;
            application = new JobApplication();
            application.setPosting(posting);
            application.setStudent(userRepository.getReferenceById(studentUserId));
            application.setStatus(ApplicationStatus.APPLIED);
            application.setAppliedAt(now);
            application.setSource(ApplicationSource.EASY_APPLY);
            application.setCreatedBy(studentUserId);
            application.setUpdatedBy(studentUserId);
            attachResume(application, studentUserId);
        }

        try {
            application = applicationRepository.saveAndFlush(application);
        } catch (DataIntegrityViolationException e) {
            // Two simultaneous taps: one insert wins, the other lands here. The
            // constraint is what makes this correct rather than the check above.
            log.info("Duplicate application rejected by constraint: postingId={} studentUserId={}",
                    postingId, studentUserId);
            throw new DriveExceptions.AlreadyAppliedException();
        }

        recordHistory(application, from, ApplicationStatus.APPLIED, studentUserId,
                ActorKind.STUDENT, "Easy Apply");

        // The student's coding data, captured as of this moment. Same transaction
        // as the application: queueing it is two or three small inserts and never
        // touches a coding platform, so it adds milliseconds, not a scrape.
        codingCaptureService.captureForApplication(application, posting, now);

        // Queued inside the apply transaction, so the receipt and the application
        // are the same fact. An application that commits without its receipt is
        // the case a student later disputes and nobody can settle.
        driveNotificationService.queueApplicationSubmitted(postingId, studentUserId);

        log.info("Application submitted: applicationId={} postingId={} studentUserId={}",
                application.getId(), postingId, studentUserId);
        return toResponse(application);
    }

    /**
     * Withdraw, permitted only while the window is open.
     *
     * After the deadline the pool is sealed and the applicant list has gone to the
     * company; letting a student quietly remove themselves then would desynchronise
     * the portal from what the recruiter already holds.
     */
    @Transactional
    public ApplicationResponse withdraw(Long postingId, Long studentUserId) {
        try {
            return doWithdraw(postingId, studentUserId);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected failure withdrawing from postingId={} studentUserId={}: {}",
                    postingId, studentUserId, e.getMessage(), e);
            throw new DriveExceptions.ApplicationFailedException();
        }
    }

    private ApplicationResponse doWithdraw(Long postingId, Long studentUserId) {
        JobPosting posting = postingRepository.findForApply(postingId)
                .orElseThrow(() -> new DriveExceptions.DriveNotVisibleException(postingId));
        assertWindowOpen(posting);

        JobApplication application = applicationRepository
                .findByPosting_IdAndStudent_IdAndIsDeletedFalse(postingId, studentUserId)
                .orElseThrow(DriveExceptions.NotAppliedException::new);

        if (application.getStatus() != ApplicationStatus.APPLIED) {
            throw new DriveExceptions.NotAppliedException();
        }

        application.setStatus(ApplicationStatus.WITHDRAWN);
        application.setWithdrawnAt(Instant.now());
        application.setUpdatedBy(studentUserId);
        applicationRepository.save(application);

        recordHistory(application, ApplicationStatus.APPLIED, ApplicationStatus.WITHDRAWN,
                studentUserId, ActorKind.STUDENT, "Withdrawn by student");

        driveNotificationService.queueApplicationWithdrawn(postingId, studentUserId);

        log.info("Application withdrawn: applicationId={} postingId={} studentUserId={}",
                application.getId(), postingId, studentUserId);
        return toResponse(application);
    }

    @Transactional(readOnly = true)
    public List<ApplicationResponse> myApplications(Long studentUserId) {
        try {
            List<JobApplication> applications =
                    applicationRepository.findByStudent_IdAndIsDeletedFalse(studentUserId);
            List<ApplicationResponse> out = new ArrayList<>(applications.size());
            for (JobApplication a : applications) {
                out.add(toResponse(a));
            }
            return out;
        } catch (Exception e) {
            log.error("Failed to list applications for studentUserId={}: {}", studentUserId, e.getMessage(), e);
            throw new DriveExceptions.InvalidRequestException(
                    "Could not load your applications right now. Please try again.");
        }
    }

    private void assertWindowOpen(JobPosting posting) {
        if (!PostingStatusTransitions.isAcceptingApplications(posting.getStatus())) {
            throw new DriveExceptions.ApplicationWindowClosedException(
                    "This drive is " + posting.getStatus() + " and is no longer accepting applications.");
        }
        Instant now = Instant.now();
        if (posting.getApplicationOpensAt() != null && now.isBefore(posting.getApplicationOpensAt())) {
            throw new DriveExceptions.ApplicationWindowClosedException(
                    "Applications for this drive open at " + posting.getApplicationOpensAt() + ".");
        }
        if (!now.isBefore(posting.getApplicationDeadline())) {
            // Reached when the sweep has not run yet but the deadline has passed.
            // The server clock is the authority, not the posting's status.
            throw new DriveExceptions.ApplicationWindowClosedException(
                    "The deadline for this drive passed at " + posting.getApplicationDeadline() + ".");
        }
    }

    void recordHistory(JobApplication application, ApplicationStatus from, ApplicationStatus to,
                       Long actorUserId, ActorKind actorKind, String reason) {
        ApplicationStatusHistory history = new ApplicationStatusHistory();
        history.setApplication(application);
        history.setFromStatus(from);
        history.setToStatus(to);
        history.setChangedAt(Instant.now());
        history.setChangedBy(actorUserId);
        history.setActorKind(actorKind);
        history.setReason(reason);
        history.setCreatedBy(actorUserId);
        history.setUpdatedBy(actorUserId);
        historyRepository.save(history);
    }

    ApplicationResponse toResponse(JobApplication a) {
        JobPosting p = a.getPosting();
        return new ApplicationResponse(
                a.getId(), p.getId(), p.getCompany().getName(), p.getTitle(),
                a.getStudentUserId(), a.getStatus(), a.getSource(),
                a.getAppliedAt(), a.getWithdrawnAt(), a.getSealedAt(),
                p.getApplicationDeadline());
    }

    /**
     * Records WHICH resume the student applied with.
     *
     * Deliberately a snapshot taken at apply time, not a live lookup. A student
     * who uploads a new resume next week has not changed what a company already
     * received, and an admin opening the applicant list months later must see the
     * document that was actually submitted. The columns on JobApplication existed
     * for this from the start; nothing populated them until now.
     *
     * Applying without a resume is allowed -- the TnP may still want the student
     * in the pool -- so a missing resume leaves the columns null rather than
     * refusing the application.
     */
    private void attachResume(JobApplication application, Long studentUserId) {
        try {
            documentRepository
                    .findByStudent_IdAndDocTypeAndIsPrimaryTrueAndIsDeletedFalse(
                            studentUserId, StudentDocumentType.RESUME)
                    .ifPresentOrElse(doc -> {
                        application.setResumeStorageKey(doc.getStorageKey());
                        application.setResumeUrl(doc.getUrl());
                        application.setResumeFileName(doc.getFileName());
                        log.info("Resume attached to application: studentUserId={} key={}",
                                studentUserId, doc.getStorageKey());
                    }, () -> log.info("No primary resume on file for studentUserId={}; applying without one",
                            studentUserId));
        } catch (Exception e) {
            // A storage-lookup failure must not cost the student their
            // application, least of all at a deadline.
            log.error("Could not attach resume for studentUserId={}: {}", studentUserId, e.getMessage(), e);
        }
    }
}
