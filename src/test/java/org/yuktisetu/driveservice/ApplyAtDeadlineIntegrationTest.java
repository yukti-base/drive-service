package org.yuktisetu.driveservice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.yuktisetu.core.exception.ApiException;
import org.yuktisetu.drive.db.*;
import org.yuktisetu.drive.model.*;
import org.yuktisetu.drive.repository.*;
import org.yuktisetu.driveservice.service.ApplicationService;
import org.yuktisetu.driveservice.service.DeadlineSealingService;
import org.yuktisetu.identity.db.User;
import org.yuktisetu.identity.model.UserStatus;
import org.yuktisetu.identity.repository.UserRepository;
import org.yuktisetu.profile.db.StudentProfile;
import org.yuktisetu.profile.repository.StudentProfileRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Database-backed cover for the apply path — the single most contended piece of
 * logic in the system.
 *
 * At 11:59 PM on a deadline, ~2500 students hit this method against one posting
 * row. Its correctness rests on a PESSIMISTIC_READ taken by the apply and a
 * PESSIMISTIC_WRITE taken by the sealing sweep, both in the same lock manager.
 * That lock pair is also the hard constraint on the migration: it is why
 * `job_postings` and `job_applications` can never live in separate databases.
 *
 * The failure it prevents is silent by construction — an application that
 * commits AFTER the pool was sealed sits at APPLIED forever, outside the pool,
 * invisible to the shortlisting engine and to the student.
 *
 * Not @Transactional, for the reason documented in DeadlineSealingIntegrationTest.
 */
@SpringBootTest
@ActiveProfiles("test")
class ApplyAtDeadlineIntegrationTest {

    @Autowired ApplicationService applicationService;
    @Autowired DeadlineSealingService sealingService;
    @Autowired JobPostingRepository postingRepository;
    @Autowired JobApplicationRepository applicationRepository;
    @Autowired CompanyRepository companyRepository;
    @Autowired PostingEligibilityCriteriaRepository criteriaRepository;
    @Autowired PostingReapplicationPolicyRepository policyRepository;
    @Autowired PostingEligibleStudentRepository eligibleStudentRepository;
    @Autowired EligibilityRunRepository runRepository;
    @Autowired UserRepository userRepository;
    @Autowired StudentProfileRepository profileRepository;

    /** Unique-constraint safety: email and phone are unique on users. */
    private static final AtomicLong SEQ = new AtomicLong(700000);

    // ---------------------------------------------------------------- seeding

    private User aStudent() {
        long n = SEQ.incrementAndGet();
        Date now = new Date();
        User u = User.builder()
                .email("s" + n + "@example.test")
                .phone("9" + n)                       // present -> profileComplete true
                .password("x")
                .firstName("Test").lastName("Student")
                .status(UserStatus.ACTIVE)
                .createdAt(now).updatedAt(now).isDeleted(false)
                .build();
        u = userRepository.save(u);

        profileRepository.save(StudentProfile.builder()
                .user(u).graduationYear(2027).cgpa(8.0)
                .activeBacklogs(0).totalBacklogs(0)
                .createdAt(now).updatedAt(now)
                .build());
        return u;
    }

    private JobPosting aPublishedPosting(Instant deadline) {
        Company c = new Company();
        c.setName("Test Co");
        c.setCode("TC-" + UUID.randomUUID().toString().substring(0, 8));
        c.setStatus(CompanyStatus.ACTIVE);
        c = companyRepository.save(c);

        JobPosting p = new JobPosting();
        p.setCompany(c);
        p.setPostingCode("PC-" + UUID.randomUUID().toString().substring(0, 8));
        p.setTitle("Systems Engineer");
        p.setJobType(JobType.FULL_TIME);
        p.setStatus(PostingStatus.PUBLISHED);
        p.setApplicationDeadline(deadline);
        p.setAcademicYear("2026-27");
        p = postingRepository.save(p);

        // Every gate off: this suite is about the apply/seal race, not eligibility
        // rules -- those are pinned exhaustively in drive-dal's unit tests.
        PostingEligibilityCriteria crit = new PostingEligibilityCriteria();
        crit.setPosting(p);
        criteriaRepository.save(crit);

        PostingReapplicationPolicy pol = new PostingReapplicationPolicy();
        pol.setPosting(p);
        policyRepository.save(pol);
        return p;
    }

    /** Puts a student into the posting's resolved eligible set. */
    private void makeEligible(JobPosting posting, User student) {
        EligibilityRun run = new EligibilityRun();
        run.setPosting(posting);
        run.setRunReason(EligibilityRunReason.PUBLISH);
        run.setStatus(EligibilityRunStatus.COMPLETED);
        run.setStartedAt(Instant.now());
        run = runRepository.save(run);

        PostingEligibleStudent e = new PostingEligibleStudent();
        e.setPosting(posting);
        e.setStudent(student);
        e.setEvaluationRun(run);
        e.setEvaluatedAt(Instant.now());
        eligibleStudentRepository.save(e);
    }

    private List<JobApplication> poolOf(JobPosting p) {
        return applicationRepository
                .findByPosting_IdAndIsDeletedFalse(p.getId(), Pageable.unpaged()).getContent();
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("an eligible student can apply while the window is open")
    void eligibleStudentCanApply() {
        JobPosting p = aPublishedPosting(Instant.now().plus(2, ChronoUnit.HOURS));
        User s = aStudent();
        makeEligible(p, s);

        applicationService.apply(p.getId(), s.getId());

        List<JobApplication> pool = poolOf(p);
        assertEquals(1, pool.size());
        assertEquals(ApplicationStatus.APPLIED, pool.get(0).getStatus());
        assertEquals(s.getId(), pool.get(0).getStudentUserId());
    }

    /**
     * Visibility is enforced through the resolved eligible set, and an ineligible
     * student gets "not visible" rather than "not allowed" — deliberately, so the
     * posting id space cannot be walked to discover drives.
     */
    @Test
    @DisplayName("a student outside the eligible set cannot apply, and is told the drive is not visible")
    void ineligibleStudentCannotApply() {
        JobPosting p = aPublishedPosting(Instant.now().plus(2, ChronoUnit.HOURS));
        User outsider = aStudent();               // deliberately NOT made eligible

        assertThrows(ApiException.class,
                () -> applicationService.apply(p.getId(), outsider.getId()));
        assertTrue(poolOf(p).isEmpty());
    }

    @Test
    @DisplayName("applying twice does not create a second application")
    void applyingTwiceIsRejected() {
        JobPosting p = aPublishedPosting(Instant.now().plus(2, ChronoUnit.HOURS));
        User s = aStudent();
        makeEligible(p, s);

        applicationService.apply(p.getId(), s.getId());
        assertThrows(ApiException.class, () -> applicationService.apply(p.getId(), s.getId()));

        assertEquals(1, poolOf(p).size(), "the unique (posting, student) constraint is the last defence");
    }

    /**
     * Withdraw-then-reapply must revive the existing row rather than insert a
     * second one — the unique constraint on (posting, student) would reject a
     * duplicate, so a naive implementation fails at the worst possible moment.
     */
    @Test
    @DisplayName("withdrawing and re-applying revives the same row, never duplicates it")
    void withdrawThenReapplyRevivesTheRow() {
        JobPosting p = aPublishedPosting(Instant.now().plus(2, ChronoUnit.HOURS));
        User s = aStudent();
        makeEligible(p, s);

        applicationService.apply(p.getId(), s.getId());
        Long firstId = poolOf(p).get(0).getId();

        applicationService.withdraw(p.getId(), s.getId());
        assertEquals(ApplicationStatus.WITHDRAWN, poolOf(p).get(0).getStatus());

        applicationService.apply(p.getId(), s.getId());

        List<JobApplication> pool = poolOf(p);
        assertEquals(1, pool.size(), "re-applying must not insert a second row");
        assertEquals(firstId, pool.get(0).getId(), "the original row is revived");
        assertEquals(ApplicationStatus.APPLIED, pool.get(0).getStatus());
    }

    // --------------------------------------------------------- the deadline

    @Test
    @DisplayName("applying after the deadline is refused")
    void cannotApplyAfterDeadline() {
        JobPosting p = aPublishedPosting(Instant.now().minus(1, ChronoUnit.MINUTES));
        User s = aStudent();
        makeEligible(p, s);

        assertThrows(ApiException.class, () -> applicationService.apply(p.getId(), s.getId()),
                "the window check is what stops a late application entering an open pool");
        assertTrue(poolOf(p).isEmpty());
    }

    /**
     * The race, played out in sequence: seal first, then attempt to apply. The
     * posting is no longer PUBLISHED, so the apply must be refused outright.
     * If it were admitted it would sit at APPLIED outside the sealed pool —
     * the exact silent failure the lock pair exists to prevent.
     */
    @Test
    @DisplayName("once the pool is sealed, a further application cannot slip in")
    void cannotApplyAfterSealing() {
        JobPosting p = aPublishedPosting(Instant.now().plus(1, ChronoUnit.SECONDS));
        User early = aStudent();
        User late = aStudent();
        makeEligible(p, early);
        makeEligible(p, late);

        applicationService.apply(p.getId(), early.getId());

        // Deadline passes, sweep runs.
        p.setApplicationDeadline(Instant.now().minus(1, ChronoUnit.MINUTES));
        postingRepository.save(p);
        assertTrue(sealingService.seal(p.getId()));

        assertThrows(ApiException.class, () -> applicationService.apply(p.getId(), late.getId()));

        List<JobApplication> pool = poolOf(p);
        assertEquals(1, pool.size(), "only the application made before sealing exists");
        assertEquals(ApplicationStatus.IN_REVIEW, pool.get(0).getStatus(),
                "and it is inside the sealed pool, not stranded at APPLIED");
    }

    @Test
    @DisplayName("withdrawing after the pool is sealed is refused")
    void cannotWithdrawAfterSealing() {
        JobPosting p = aPublishedPosting(Instant.now().plus(1, ChronoUnit.SECONDS));
        User s = aStudent();
        makeEligible(p, s);
        applicationService.apply(p.getId(), s.getId());

        p.setApplicationDeadline(Instant.now().minus(1, ChronoUnit.MINUTES));
        postingRepository.save(p);
        sealingService.seal(p.getId());

        assertThrows(ApiException.class, () -> applicationService.withdraw(p.getId(), s.getId()),
                "a student cannot leave a pool that has already been handed to shortlisting");
    }
}
