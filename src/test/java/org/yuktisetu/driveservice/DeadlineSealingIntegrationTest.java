package org.yuktisetu.driveservice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.yuktisetu.drive.db.Company;
import org.yuktisetu.drive.db.JobApplication;
import org.yuktisetu.drive.db.JobPosting;
import org.yuktisetu.drive.model.ApplicationStatus;
import org.yuktisetu.drive.model.CompanyStatus;
import org.yuktisetu.drive.model.JobType;
import org.yuktisetu.drive.model.PostingStatus;
import org.yuktisetu.drive.repository.CompanyRepository;
import org.yuktisetu.drive.repository.JobApplicationRepository;
import org.yuktisetu.drive.repository.JobPostingRepository;
import org.yuktisetu.driveservice.service.DeadlineSealingService;
import org.yuktisetu.identity.db.User;
import org.yuktisetu.identity.model.UserStatus;
import org.yuktisetu.identity.repository.UserRepository;

import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Date;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Database-backed cover for deadline sealing — the step that closes a drive and
 * hands its applicant pool to the (unbuilt) shortlisting engine.
 *
 * Why this one is worth a real database: the behaviour under test is a bulk
 * UPDATE issued inside the same transaction that holds a PESSIMISTIC_WRITE lock
 * on the posting row. None of that is observable from a unit test, and its
 * documented failure mode is invisible — an application stranded at APPLIED,
 * outside the sealed pool, with nothing in the logs.
 *
 * Phase 5 of the migration moves the posting lifecycle into drive-service
 * alongside this code. These assertions are what prove the move changed nothing.
 *
 * Runs against the throwaway yuktisetu_test database with ddl-auto=create-drop.
 * Seeds are deliberately tiny — a handful of rows, never a bulk fixture.
 *
 * Note these tests are deliberately NOT @Transactional. DeadlineSealingService.seal
 * is @Transactional(REQUIRES_NEW), so it runs in its own transaction and cannot
 * see writes an enclosing test transaction has not committed — exactly as the
 * scheduler sees only committed data in production. Wrapping the tests would make
 * every seal() return false for a reason that has nothing to do with the code
 * under test. The schema is dropped at context shutdown, so nothing survives.
 */
@SpringBootTest
@ActiveProfiles("test")
class DeadlineSealingIntegrationTest {

    @Autowired DeadlineSealingService sealingService;
    @Autowired JobPostingRepository postingRepository;
    @Autowired JobApplicationRepository applicationRepository;
    @Autowired CompanyRepository companyRepository;
    @Autowired UserRepository userRepository;

    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong(600000);

    // ---------------------------------------------------------------- seeding

    private Company aCompany() {
        Company c = new Company();
        c.setName("Test Co");
        c.setCode("TC-" + UUID.randomUUID().toString().substring(0, 8));
        c.setStatus(CompanyStatus.ACTIVE);
        return companyRepository.save(c);
    }

    /** A published posting whose deadline has already passed — i.e. due for sealing. */
    private JobPosting aPostingDueForSealing() {
        JobPosting p = new JobPosting();
        p.setCompany(aCompany());
        p.setPostingCode("PC-" + UUID.randomUUID().toString().substring(0, 8));
        p.setTitle("Systems Engineer");
        p.setJobType(JobType.FULL_TIME);
        p.setStatus(PostingStatus.PUBLISHED);
        p.setApplicationDeadline(Instant.now().minus(1, ChronoUnit.HOURS));
        p.setAcademicYear("2026-27");
        return postingRepository.save(p);
    }

    /**
     * JobApplication.student is a real @ManyToOne to User, so a seeded
     * application needs a real identity row -- a bare id will not do.
     */
    private User aStudent() {
        long n = SEQ.incrementAndGet();
        Date now = new Date();
        return userRepository.save(User.builder()
                .email("seal" + n + "@example.test").phone("6" + String.format("%09d", n))
                .password("x").firstName("Seal").lastName("Test")
                .status(UserStatus.ACTIVE)
                .createdAt(now).updatedAt(now).isDeleted(false).build());
    }

    private JobApplication anApplication(JobPosting posting, ApplicationStatus status) {
        JobApplication a = new JobApplication();
        a.setPosting(posting);
        a.setStudent(aStudent());
        a.setStatus(status);
        a.setAppliedAt(Instant.now().minus(2, ChronoUnit.HOURS));
        return applicationRepository.save(a);
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("sealing closes the posting and moves every APPLIED row to IN_REVIEW")
    void sealingClosesPostingAndPool() {
        JobPosting posting = aPostingDueForSealing();
        anApplication(posting, ApplicationStatus.APPLIED);
        anApplication(posting, ApplicationStatus.APPLIED);

        boolean sealed = sealingService.seal(posting.getId());
        assertTrue(sealed, "a published posting past its deadline must seal");

        JobPosting after = postingRepository.findById(posting.getId()).orElseThrow();
        assertEquals(PostingStatus.APPLICATIONS_CLOSED, after.getStatus());
        assertNotNull(after.getApplicationsClosedAt(), "the close timestamp is the audit record");

        List<JobApplication> pool = applicationRepository
                .findByPosting_IdAndIsDeletedFalse(posting.getId(), Pageable.unpaged()).getContent();
        assertEquals(2, pool.size());
        pool.forEach(a -> assertEquals(ApplicationStatus.IN_REVIEW, a.getStatus(),
                "an APPLIED row left behind is invisible to the shortlisting engine"));
    }

    @Test
    @DisplayName("a withdrawn application is NOT swept into the sealed pool")
    void withdrawnApplicationsAreNotSealed() {
        JobPosting posting = aPostingDueForSealing();
        anApplication(posting, ApplicationStatus.APPLIED);
        anApplication(posting, ApplicationStatus.WITHDRAWN);

        sealingService.seal(posting.getId());

        List<JobApplication> pool = applicationRepository
                .findByPosting_IdAndIsDeletedFalse(posting.getId(), Pageable.unpaged()).getContent();
        long inReview = pool.stream().filter(a -> a.getStatus() == ApplicationStatus.IN_REVIEW).count();
        long withdrawn = pool.stream().filter(a -> a.getStatus() == ApplicationStatus.WITHDRAWN).count();

        assertEquals(1, inReview, "only the live application enters the pool");
        assertEquals(1, withdrawn, "a student who withdrew must not be shortlisted");
    }

    @Test
    @DisplayName("sealing is idempotent -- a second sweep over the same posting is a no-op")
    void sealingTwiceIsANoOp() {
        JobPosting posting = aPostingDueForSealing();
        anApplication(posting, ApplicationStatus.APPLIED);

        assertTrue(sealingService.seal(posting.getId()));
        assertFalse(sealingService.seal(posting.getId()),
                "two scheduler instances must not both seal; the status re-check under the lock is what stops it");
    }

    @Test
    @DisplayName("a posting whose deadline has not passed is not sealed")
    void futureDeadlineIsNotSealed() {
        JobPosting posting = aPostingDueForSealing();
        posting.setApplicationDeadline(Instant.now().plus(2, ChronoUnit.HOURS));
        postingRepository.save(posting);

        assertFalse(sealingService.seal(posting.getId()),
                "sealing before the deadline would lock students out early");

        assertEquals(PostingStatus.PUBLISHED,
                postingRepository.findById(posting.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("findDue() picks up exactly the postings past their deadline")
    void findDueSelectsOnlyOverduePostings() {
        JobPosting overdue = aPostingDueForSealing();

        JobPosting future = aPostingDueForSealing();
        future.setApplicationDeadline(Instant.now().plus(3, ChronoUnit.HOURS));
        postingRepository.save(future);

        List<Long> due = sealingService.findDue();

        assertTrue(due.contains(overdue.getId()), "an overdue drive must be swept");
        assertFalse(due.contains(future.getId()), "a live drive must not be swept");
    }
}
