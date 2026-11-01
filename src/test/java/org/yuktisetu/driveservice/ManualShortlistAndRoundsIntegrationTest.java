package org.yuktisetu.driveservice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.yuktisetu.core.exception.ApiException;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.drive.db.Company;
import org.yuktisetu.drive.db.EligibilityRun;
import org.yuktisetu.drive.db.JobPosting;
import org.yuktisetu.drive.db.PostingEligibilityCriteria;
import org.yuktisetu.drive.db.PostingEligibleStudent;
import org.yuktisetu.drive.db.PostingReapplicationPolicy;
import org.yuktisetu.drive.model.ApplicationStatus;
import org.yuktisetu.drive.model.CompanyStatus;
import org.yuktisetu.drive.model.EligibilityRunReason;
import org.yuktisetu.drive.model.EligibilityRunStatus;
import org.yuktisetu.drive.model.JobType;
import org.yuktisetu.drive.model.PostingStatus;
import org.yuktisetu.drive.model.ShortlistingMode;
import org.yuktisetu.drive.repository.CompanyRepository;
import org.yuktisetu.drive.repository.DriveScoringGateRepository;
import org.yuktisetu.drive.repository.EligibilityRunRepository;
import org.yuktisetu.drive.repository.JobApplicationRepository;
import org.yuktisetu.drive.repository.JobPostingRepository;
import org.yuktisetu.drive.repository.PostingEligibilityCriteriaRepository;
import org.yuktisetu.drive.repository.PostingEligibleStudentRepository;
import org.yuktisetu.drive.repository.PostingReapplicationPolicyRepository;
import org.yuktisetu.driveservice.dto.ReopenDriveRequest;
import org.yuktisetu.driveservice.dto.ScoreResponses.EmailResolution;
import org.yuktisetu.driveservice.dto.ScoreResponses.StaffDriveScores;
import org.yuktisetu.driveservice.dto.StudentDriveDetailResponse;
import org.yuktisetu.driveservice.service.ApplicationService;
import org.yuktisetu.driveservice.service.DeadlineSealingService;
import org.yuktisetu.driveservice.service.DriveReopenService;
import org.yuktisetu.driveservice.service.ShortlistService;
import org.yuktisetu.driveservice.service.StudentDriveService;
import org.yuktisetu.identity.db.User;
import org.yuktisetu.identity.model.UserStatus;
import org.yuktisetu.identity.repository.UserRepository;
import org.yuktisetu.profile.db.StudentProfile;
import org.yuktisetu.profile.repository.StudentProfileRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The shortlist for drives the engine does not rank (MANUAL, COMPANY_SIDE, FCFS),
 * the reopen guard once rounds start, and the student's own view of the rounds
 * (decided 2026-10-30). Not @Transactional; runs against yuktisetu_test.
 */
@SpringBootTest
@ActiveProfiles("test")
class ManualShortlistAndRoundsIntegrationTest {

    @Autowired ApplicationService applicationService;
    @Autowired DeadlineSealingService sealingService;
    @Autowired ShortlistService shortlistService;
    @Autowired DriveReopenService reopenService;
    @Autowired StudentDriveService studentDriveService;
    @Autowired JobPostingRepository postingRepository;
    @Autowired JobApplicationRepository applicationRepository;
    @Autowired DriveScoringGateRepository gateRepository;
    @Autowired CompanyRepository companyRepository;
    @Autowired PostingEligibilityCriteriaRepository criteriaRepository;
    @Autowired PostingReapplicationPolicyRepository policyRepository;
    @Autowired PostingEligibleStudentRepository eligibleStudentRepository;
    @Autowired EligibilityRunRepository eligibilityRunRepository;
    @Autowired UserRepository userRepository;
    @Autowired StudentProfileRepository profileRepository;
    @Autowired JdbcTemplate jdbc;

    private static final AtomicLong SEQ = new AtomicLong(800000);

    private static final UserPrincipal TNP = new UserPrincipal(1L, "tnp@example.test",
            List.of(new UserPrincipal.RoleClaim("TNP_SUPER_ADMIN", null, null)));

    // ---------------------------------------------------------------- seeding

    private User aStudent() {
        long n = SEQ.incrementAndGet();
        Date now = new Date();
        User u = userRepository.save(User.builder()
                .email("manual" + n + "@example.test").phone("72" + n).password("x")
                .firstName("Manual").lastName("Student" + n).status(UserStatus.ACTIVE)
                .createdAt(now).updatedAt(now).isDeleted(false).build());
        profileRepository.save(StudentProfile.builder().user(u).graduationYear(2027).cgpa(8.0)
                .activeBacklogs(0).totalBacklogs(0).createdAt(now).updatedAt(now).build());
        return u;
    }

    private JobPosting aPosting(ShortlistingMode mode, Integer seats) {
        Company c = new Company();
        c.setName("Manual Co");
        c.setCode("MC-" + UUID.randomUUID().toString().substring(0, 8));
        c.setStatus(CompanyStatus.ACTIVE);
        c = companyRepository.save(c);
        JobPosting p = new JobPosting();
        p.setCompany(c);
        p.setPostingCode("MP-" + UUID.randomUUID().toString().substring(0, 8));
        p.setTitle("Analyst");
        p.setJobType(JobType.FULL_TIME);
        p.setStatus(PostingStatus.PUBLISHED);
        p.setApplicationDeadline(Instant.now().plusSeconds(7200));
        p.setAcademicYear("2026-27");
        p.setShortlistingMode(mode);
        p.setMaxApplications(seats);
        p = postingRepository.save(p);
        PostingEligibilityCriteria crit = new PostingEligibilityCriteria();
        crit.setPosting(p);
        criteriaRepository.save(crit);
        PostingReapplicationPolicy pol = new PostingReapplicationPolicy();
        pol.setPosting(p);
        policyRepository.save(pol);
        return p;
    }

    private List<User> applicants(JobPosting p, int n) {
        EligibilityRun run = new EligibilityRun();
        run.setPosting(p);
        run.setRunReason(EligibilityRunReason.PUBLISH);
        run.setStatus(EligibilityRunStatus.COMPLETED);
        run.setStartedAt(Instant.now());
        run = eligibilityRunRepository.save(run);
        List<User> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            User s = aStudent();
            PostingEligibleStudent e = new PostingEligibleStudent();
            e.setPosting(p);
            e.setStudent(s);
            e.setEvaluationRun(run);
            e.setEvaluatedAt(Instant.now());
            eligibleStudentRepository.save(e);
            applicationService.apply(p.getId(), s.getId());
            out.add(s);
        }
        return out;
    }

    private void seal(JobPosting p) {
        JobPosting fresh = postingRepository.findById(p.getId()).orElseThrow();
        fresh.setApplicationDeadline(Instant.now().minusSeconds(1));
        postingRepository.save(fresh);
        assertTrue(sealingService.seal(p.getId()));
    }

    private long appOf(JobPosting p, User s) {
        return applicationRepository.findByPosting_IdAndStudent_IdAndIsDeletedFalse(p.getId(), s.getId()).orElseThrow().getId();
    }

    private ApplicationStatus status(JobPosting p, User s) {
        return applicationRepository.findById(appOf(p, s)).orElseThrow().getStatus();
    }

    private long round(JobPosting p, int seq, String name, String status) {
        jdbc.update("""
                INSERT INTO drive_rounds (posting_id, seq, name, round_type, status, marks_version, is_final,
                                          created_at, updated_at, version)
                VALUES (?, ?, ?, 'TECHNICAL_INTERVIEW', ?, 0, false, now(), now(), 0)
                """, p.getId(), seq, name, status);
        return jdbc.queryForObject("SELECT max(id) FROM drive_rounds", Long.class);
    }

    private void result(long roundId, JobPosting p, User s, String outcome) {
        jdbc.update("""
                INSERT INTO round_results (round_id, posting_id, application_id, student_user_id, outcome, source,
                                           marked_by, marked_at, version)
                VALUES (?, ?, ?, ?, ?, 'PORTAL', 1, now(), 0)
                """, roundId, p.getId(), appOf(p, s), s.getId(), outcome);
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("a MANUAL drive: no scoring gate; the TnP's list (pasted emails) is resolved, then published")
    void manualShortlistFromEmails() {
        JobPosting p = aPosting(ShortlistingMode.MANUAL, null);
        List<User> s = applicants(p, 4);
        User outsider = aStudent();
        seal(p);
        assertTrue(gateRepository.findByPostingId(p.getId()).isEmpty(), "no scoring gate for a manual drive");

        StaffDriveScores screen = shortlistService.scores(TNP, p.getId());
        assertTrue(screen.canPublish(), "a manual drive can be shortlisted without a scoring run");
        assertEquals(4, screen.items().size());

        EmailResolution r = shortlistService.resolveEmails(TNP, p.getId(),
                "From the company:\n" + s.get(0).getEmail().toUpperCase() + "\n" + s.get(1).getEmail() + ", "
                        + outsider.getEmail() + "\nnobody@nowhere.test\n" + s.get(0).getEmail());
        assertEquals(2, r.matched().size());
        assertEquals(List.of(outsider.getEmail()), r.notApplied());
        assertEquals(List.of("nobody@nowhere.test"), r.noAccount());
        assertEquals(1, r.duplicates().get(s.get(0).getEmail().toLowerCase()));

        shortlistService.publish(TNP, p.getId(), r.matched().stream().map(EmailResolution.Match::applicationId).toList());
        assertEquals(ApplicationStatus.SHORTLISTED, status(p, s.get(0)));
        assertEquals(ApplicationStatus.SHORTLISTED, status(p, s.get(1)));
        assertEquals(ApplicationStatus.REJECTED, status(p, s.get(2)));
        assertNotNull(postingRepository.findById(p.getId()).orElseThrow().getShortlistPublishedAt());

        ApiException again = assertThrows(ApiException.class, () -> shortlistService.publish(TNP, p.getId(), List.of()));
        assertEquals("SHORTLIST_PUBLISHED", again.getCode());
    }

    @Test
    @DisplayName("FCFS: every seat holder is shortlisted as the pool seals, so rounds can start")
    void fcfsShortlistsAtSeal() {
        JobPosting p = aPosting(ShortlistingMode.FCFS, 5);
        List<User> s = applicants(p, 3);
        seal(p);
        for (User u : s) {
            assertEquals(ApplicationStatus.SHORTLISTED, status(p, u));
        }
        assertNotNull(postingRepository.findById(p.getId()).orElseThrow().getShortlistPublishedAt());
        ApiException e = assertThrows(ApiException.class, () -> shortlistService.publish(TNP, p.getId(), List.of()));
        assertEquals("SHORTLIST_PUBLISHED", e.getCode());
    }

    @Test
    @DisplayName("once anyone is marked in a round -- even a draft -- the drive cannot be reopened")
    void reopenRefusedOnceRoundsStart() {
        JobPosting p = aPosting(ShortlistingMode.MANUAL, null);
        List<User> s = applicants(p, 2);
        seal(p);
        shortlistService.publish(TNP, p.getId(), List.of(appOf(p, s.get(0))));
        long r1 = round(p, 1, "Interview", "PLANNED");
        result(r1, p, s.get(0), "ADVANCED");

        ApiException e = assertThrows(ApiException.class, () -> reopenService.reopen(TNP, p.getId(),
                new ReopenDriveRequest(Instant.now().plusSeconds(3600), "Company wants more applicants")));
        assertEquals("ROUNDS_STARTED", e.getCode());
        assertEquals(PostingStatus.APPLICATIONS_CLOSED, postingRepository.findById(p.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("a student sees their own published results only: the rounds up to where they went out, never a draft")
    void studentSeesOwnRounds() {
        JobPosting p = aPosting(ShortlistingMode.MANUAL, null);
        List<User> s = applicants(p, 3);
        seal(p);
        shortlistService.publish(TNP, p.getId(), List.of(appOf(p, s.get(0)), appOf(p, s.get(1))));
        long r1 = round(p, 1, "Aptitude", "PUBLISHED");
        long r2 = round(p, 2, "Interview", "PLANNED");
        round(p, 3, "HR", "PLANNED");
        result(r1, p, s.get(0), "ADVANCED");
        result(r1, p, s.get(1), "ABSENT");
        jdbc.update("UPDATE job_applications SET status = 'REJECTED' WHERE id = ?", appOf(p, s.get(1)));
        result(r2, p, s.get(0), "NOT_ADVANCED");   // a DRAFT: round 2 is not published

        StudentDriveDetailResponse in = studentDriveService.getForStudent(p.getId(), s.get(0).getId());
        assertEquals(3, in.rounds().size(), "still in the drive: every round, upcoming ones too");
        assertEquals("ADVANCED", in.rounds().get(0).myOutcome());
        assertNull(in.rounds().get(1).myOutcome(), "a draft mark is never shown");
        assertTrue(in.rounds().get(1).current());

        StudentDriveDetailResponse out = studentDriveService.getForStudent(p.getId(), s.get(1).getId());
        assertEquals(1, out.rounds().size(), "out in round 1: later rounds are not theirs");
        assertEquals("Absent", out.rounds().get(0).myOutcomeLabel());

        StudentDriveDetailResponse rejectedAtShortlist = studentDriveService.getForStudent(p.getId(), s.get(2).getId());
        assertTrue(rejectedAtShortlist.rounds().isEmpty(), "not shortlisted: no rounds");
    }
}
