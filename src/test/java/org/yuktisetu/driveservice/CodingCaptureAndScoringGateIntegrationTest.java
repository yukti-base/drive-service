package org.yuktisetu.driveservice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.yuktisetu.drive.db.ApplicationCodingCapture;
import org.yuktisetu.drive.db.Company;
import org.yuktisetu.drive.db.DriveScoringGate;
import org.yuktisetu.drive.db.EligibilityRun;
import org.yuktisetu.drive.db.JobApplication;
import org.yuktisetu.drive.db.JobPosting;
import org.yuktisetu.drive.db.PostingEligibilityCriteria;
import org.yuktisetu.drive.db.PostingEligibleStudent;
import org.yuktisetu.drive.db.PostingReapplicationPolicy;
import org.yuktisetu.drive.model.ApplicationStatus;
import org.yuktisetu.drive.model.CaptureReviewState;
import org.yuktisetu.drive.model.CaptureState;
import org.yuktisetu.drive.model.CompanyStatus;
import org.yuktisetu.drive.model.EligibilityRunReason;
import org.yuktisetu.drive.model.EligibilityRunStatus;
import org.yuktisetu.drive.model.JobType;
import org.yuktisetu.drive.model.PostingStatus;
import org.yuktisetu.drive.model.ScoringGateState;
import org.yuktisetu.drive.model.ShortlistingMode;
import org.yuktisetu.drive.repository.ApplicationCodingCaptureRepository;
import org.yuktisetu.drive.repository.CompanyRepository;
import org.yuktisetu.drive.repository.DriveScoringGateRepository;
import org.yuktisetu.drive.repository.EligibilityRunRepository;
import org.yuktisetu.drive.repository.JobApplicationRepository;
import org.yuktisetu.drive.repository.JobPostingRepository;
import org.yuktisetu.drive.repository.PostingEligibilityCriteriaRepository;
import org.yuktisetu.drive.repository.PostingEligibleStudentRepository;
import org.yuktisetu.drive.repository.PostingReapplicationPolicyRepository;
import org.yuktisetu.core.exception.ApiException;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.drive.db.ApplicationCodingReview;
import org.yuktisetu.drive.model.CodingReviewDecision;
import org.yuktisetu.drive.repository.ApplicationCodingReviewRepository;
import org.yuktisetu.driveservice.dto.CodingReviewDecisionRequest;
import org.yuktisetu.driveservice.dto.CodingReviewResponse;
import org.yuktisetu.driveservice.service.ApplicationService;
import org.yuktisetu.driveservice.service.CodingRecheckService;
import org.yuktisetu.driveservice.service.CodingReviewService;
import org.yuktisetu.driveservice.dto.CodingCheckResponse;
import org.yuktisetu.drive.model.CaptureFlagReason;
import org.yuktisetu.messaging.events.NotificationTemplates;
import org.yuktisetu.driveservice.service.DeadlineSealingService;
import org.yuktisetu.driveservice.service.ScoringGateService;
import org.yuktisetu.identity.db.User;
import org.yuktisetu.identity.model.UserStatus;
import org.yuktisetu.identity.repository.UserRepository;
import org.yuktisetu.messaging.events.CodingScrapeCompletedEvent;
import org.yuktisetu.messaging.events.DriveTopics;
import org.yuktisetu.messaging.events.EventEnvelope;
import org.yuktisetu.profile.db.CodingProfile;
import org.yuktisetu.profile.db.StudentProfile;
import org.yuktisetu.profile.model.CodingPlatform;
import org.yuktisetu.profile.repository.StudentProfileRepository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Apply-time coding capture, and the gate that decides when a drive may be scored.
 *
 * The property everything here protects: a sealed drive reaches READY exactly
 * once, the moment its last condition settles, and never waits on anything that
 * is not guaranteed to end.
 *
 * The scraper is not running. Its two effects are simulated exactly: a finished
 * job is a row update in coding_scrape_jobs, and its announcement is a
 * CodingScrapeCompleted event -- parsed, in the contract test, from two payloads
 * the real Python scraper wrote on 2026-10-15.
 *
 * Not @Transactional, for the reason in DeadlineSealingIntegrationTest. Runs
 * against yuktisetu_test (create-drop). The partial unique index that makes two
 * applications share one scrape job lives only in the SQL migration, so it is
 * created here.
 */
@SpringBootTest
@ActiveProfiles("test")
class CodingCaptureAndScoringGateIntegrationTest {

    @Autowired ApplicationService applicationService;
    @Autowired DeadlineSealingService sealingService;
    @Autowired ScoringGateService gateService;
    @Autowired JobPostingRepository postingRepository;
    @Autowired JobApplicationRepository applicationRepository;
    @Autowired ApplicationCodingCaptureRepository captureRepository;
    @Autowired DriveScoringGateRepository gateRepository;
    @Autowired CompanyRepository companyRepository;
    @Autowired PostingEligibilityCriteriaRepository criteriaRepository;
    @Autowired PostingReapplicationPolicyRepository policyRepository;
    @Autowired PostingEligibleStudentRepository eligibleStudentRepository;
    @Autowired EligibilityRunRepository runRepository;
    @Autowired UserRepository userRepository;
    @Autowired StudentProfileRepository profileRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired CodingReviewService reviewService;
    @Autowired ApplicationCodingReviewRepository reviewRepository;
    @Autowired ObjectMapper objectMapper;
    @Autowired CodingRecheckService recheckService;

    private static final AtomicLong SEQ = new AtomicLong(800000);

    @BeforeEach
    void schemaThatOnlyTheMigrationCreates() {
        jdbc.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS uk_scrape_job_pending
                    ON coding_scrape_jobs (student_user_id, platform)
                    WHERE status IN ('PENDING','RUNNING')
                """);
        // Owned by the Python scraper, so no entity creates it. Columns as in
        // .schema/2026-10-06-coding-scrape-tables.sql plus job_id.
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS coding_profile_snapshots (
                    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    student_user_id bigint NOT NULL, platform varchar(32) NOT NULL,
                    handle varchar(128) NOT NULL, status varchar(24) NOT NULL,
                    scraped_at timestamptz NOT NULL, extraction_tier varchar(24),
                    raw jsonb, metrics jsonb, last_error text, job_id bigint)
                """);
    }

    private static final UserPrincipal TRUST_WIDE = new UserPrincipal(1L, "tnp@example.test",
            List.of(new UserPrincipal.RoleClaim("TNP_SUPER_ADMIN", null, null)));

    private static final UserPrincipal OTHER_COLLEGE_COORDINATOR = new UserPrincipal(2L, "coord@example.test",
            List.of(new UserPrincipal.RoleClaim("TNP_COORDINATOR", 999_999L, null)));

    // ---------------------------------------------------------------- seeding

    private User aStudent(Map<CodingPlatform, String> handles) {
        long n = SEQ.incrementAndGet();
        Date now = new Date();
        User u = userRepository.save(User.builder()
                .email("c" + n + "@example.test").phone("8" + n).password("x")
                .firstName("Test").lastName("Coder")
                .status(UserStatus.ACTIVE)
                .createdAt(now).updatedAt(now).isDeleted(false)
                .build());

        StudentProfile profile = StudentProfile.builder()
                .user(u).graduationYear(2027).cgpa(8.0)
                .activeBacklogs(0).totalBacklogs(0)
                .createdAt(now).updatedAt(now)
                .build();
        handles.forEach((platform, handle) -> profile.getCodingProfiles().add(
                CodingProfile.builder().studentProfile(profile).platform(platform).username(handle).build()));
        profileRepository.save(profile);
        return u;
    }

    private JobPosting aPosting(ShortlistingMode mode) {
        Company c = new Company();
        c.setName("Gate Co");
        c.setCode("GC-" + UUID.randomUUID().toString().substring(0, 8));
        c.setStatus(CompanyStatus.ACTIVE);
        c = companyRepository.save(c);

        JobPosting p = new JobPosting();
        p.setCompany(c);
        p.setPostingCode("GP-" + UUID.randomUUID().toString().substring(0, 8));
        p.setTitle("Graduate Engineer");
        p.setJobType(JobType.FULL_TIME);
        p.setStatus(PostingStatus.PUBLISHED);
        p.setApplicationDeadline(Instant.now().plus(2, ChronoUnit.HOURS));
        p.setAcademicYear("2026-27");
        p.setShortlistingMode(mode);
        p = postingRepository.save(p);

        PostingEligibilityCriteria crit = new PostingEligibilityCriteria();
        crit.setPosting(p);
        criteriaRepository.save(crit);
        PostingReapplicationPolicy pol = new PostingReapplicationPolicy();
        pol.setPosting(p);
        policyRepository.save(pol);
        return p;
    }

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

    private User applied(JobPosting p, Map<CodingPlatform, String> handles) {
        User s = aStudent(handles);
        makeEligible(p, s);
        applicationService.apply(p.getId(), s.getId());
        return s;
    }

    /** Move the deadline into the past and run the real seal. */
    private void seal(JobPosting p) {
        JobPosting fresh = postingRepository.findById(p.getId()).orElseThrow();
        fresh.setApplicationDeadline(Instant.now().minusSeconds(1));
        postingRepository.save(fresh);
        assertTrue(sealingService.seal(p.getId()));
    }

    private List<ApplicationCodingCapture> capturesOf(JobPosting p) {
        return captureRepository.findAll().stream()
                .filter(c -> c.getPostingId().equals(p.getId()))
                .toList();
    }

    private List<Map<String, Object>> jobsOf(User s) {
        return jdbc.queryForList(
                "SELECT id, platform, handle, status, reason, give_up_at FROM coding_scrape_jobs "
                        + "WHERE student_user_id = ? ORDER BY id", s.getId());
    }

    private DriveScoringGate gateOf(JobPosting p) {
        return gateRepository.findByPostingId(p.getId()).orElseThrow();
    }

    private int readyEvents(JobPosting p) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE topic = ? AND aggregate_id = ?",
                Integer.class, DriveTopics.DRIVE_READY_FOR_SCORING, String.valueOf(p.getId()));
        return n == null ? 0 : n;
    }

    /** What the Python scraper does when a job ends: the row, then the event. */
    private CodingScrapeCompletedEvent finishJob(long jobId, boolean ok, String outcome) {
        Long snapshotId = ok ? 900000L + jobId : null;
        jdbc.update("UPDATE coding_scrape_jobs SET status = ?, last_status = ?, snapshot_id = ?, "
                        + "finished_at = now(), updated_at = now() WHERE id = ?",
                ok ? "DONE" : "FAILED", outcome, snapshotId, jobId);
        Map<String, Object> job = jdbc.queryForMap(
                "SELECT student_user_id, platform, handle FROM coding_scrape_jobs WHERE id = ?", jobId);
        return new CodingScrapeCompletedEvent(jobId, ((Number) job.get("student_user_id")).longValue(),
                (String) job.get("platform"), (String) job.get("handle"),
                ok ? "DONE" : "FAILED", outcome, snapshotId, 1, ok ? null : "simulated", Instant.now());
    }

    private void deliver(CodingScrapeCompletedEvent event) {
        gateService.onScrapeCompleted(UUID.randomUUID().toString(), event);
    }

    private long jobId(User s, String platform) {
        return jobsOf(s).stream().filter(j -> platform.equals(j.get("platform")))
                .map(j -> ((Number) j.get("id")).longValue()).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------ apply time

    @Test
    @DisplayName("applying queues one scrape job and one pending capture per listed platform")
    void applyQueuesCaptures() {
        JobPosting p = aPosting(ShortlistingMode.COMPOSITE_SCORE);
        User s = applied(p, Map.of(CodingPlatform.LEETCODE, "lc_handle", CodingPlatform.CODECHEF, "cc_handle"));

        List<Map<String, Object>> jobs = jobsOf(s);
        assertEquals(2, jobs.size());
        jobs.forEach(j -> {
            assertEquals("PENDING", j.get("status"));
            assertEquals("APPLY", j.get("reason"));
            // The absolute limit is set at insert, about 70 minutes out.
            Instant giveUp = ((java.sql.Timestamp) j.get("give_up_at")).toInstant();
            assertTrue(giveUp.isAfter(Instant.now().plus(65, ChronoUnit.MINUTES)));
            assertTrue(giveUp.isBefore(Instant.now().plus(75, ChronoUnit.MINUTES)));
        });

        List<ApplicationCodingCapture> captures = capturesOf(p);
        assertEquals(2, captures.size());
        captures.forEach(c -> {
            assertEquals(CaptureState.PENDING, c.getState());
            assertNotNull(c.getJobId());
        });
    }

    @Test
    @DisplayName("a platform listed without a username goes back to the student at once, with no job")
    void blankHandleGoesToTheStudent() {
        JobPosting p = aPosting(ShortlistingMode.COMPOSITE_SCORE);
        User s = applied(p, Map.of(CodingPlatform.CODECHEF, "  "));

        assertTrue(jobsOf(s).isEmpty());
        ApplicationCodingCapture c = capturesOf(p).get(0);
        assertEquals(CaptureState.AWAITING_STUDENT, c.getState());
        assertEquals("NO_HANDLE", c.getOutcome());
        assertEquals(CaptureReviewState.NOT_REQUIRED, c.getReviewState());
        assertEquals(1, notices(s, NotificationTemplates.CODING_HANDLE_CHECK_NEEDED));
    }

    @Test
    @DisplayName("platforms with no adapter are not queued, so they cannot flag the student")
    void unsupportedPlatformsAreSkipped() {
        JobPosting p = aPosting(ShortlistingMode.COMPOSITE_SCORE);
        User s = applied(p, Map.of(CodingPlatform.GEEKSFORGEEKS, "gfg_handle"));

        assertTrue(jobsOf(s).isEmpty());
        assertTrue(capturesOf(p).isEmpty());
    }

    @Test
    @DisplayName("drives that never compute a score capture nothing")
    void nonScoringDrivesCaptureNothing() {
        JobPosting p = aPosting(ShortlistingMode.MANUAL);
        User s = applied(p, Map.of(CodingPlatform.LEETCODE, "lc_handle"));

        assertTrue(jobsOf(s).isEmpty());
        assertTrue(capturesOf(p).isEmpty());
    }

    @Test
    @DisplayName("two applications a moment apart share one scrape job, and one result fills both")
    void twoApplicationsShareOneJob() {
        JobPosting a = aPosting(ShortlistingMode.COMPOSITE_SCORE);
        JobPosting b = aPosting(ShortlistingMode.COMPOSITE_SCORE);
        User s = aStudent(Map.of(CodingPlatform.LEETCODE, "shared_handle"));
        makeEligible(a, s);
        makeEligible(b, s);
        applicationService.apply(a.getId(), s.getId());
        applicationService.apply(b.getId(), s.getId());

        assertEquals(1, jobsOf(s).size(), "the partial unique index makes the second apply join the job");
        long job = jobId(s, "LEETCODE");
        assertEquals(job, capturesOf(a).get(0).getJobId());
        assertEquals(job, capturesOf(b).get(0).getJobId());

        deliver(finishJob(job, true, "OK"));

        assertEquals(CaptureState.CAPTURED, capturesOf(a).get(0).getState());
        assertEquals(CaptureState.CAPTURED, capturesOf(b).get(0).getState());
    }

    @Test
    @DisplayName("withdrawing and re-applying retires the old capture and starts a new one")
    void reapplySupersedes() {
        JobPosting p = aPosting(ShortlistingMode.COMPOSITE_SCORE);
        User s = applied(p, Map.of(CodingPlatform.LEETCODE, "lc_handle"));
        deliver(finishJob(jobId(s, "LEETCODE"), true, "OK"));

        applicationService.withdraw(p.getId(), s.getId());
        applicationService.apply(p.getId(), s.getId());

        List<ApplicationCodingCapture> all = capturesOf(p);
        assertEquals(2, all.size());
        assertEquals(1, all.stream().filter(c -> c.getState() == CaptureState.SUPERSEDED).count());
        assertEquals(1, all.stream().filter(c -> c.getState() == CaptureState.PENDING).count());
    }

    // ---------------------------------------------------------------- the gate

    @Test
    @DisplayName("sealed while scrapes are pending: COLLECTING, then READY when the last one finishes")
    void gateOpensThenReadiesOnLastCompletion() {
        JobPosting p = aPosting(ShortlistingMode.COMPOSITE_SCORE);
        User s1 = applied(p, Map.of(CodingPlatform.LEETCODE, "one"));
        User s2 = applied(p, Map.of(CodingPlatform.CODEFORCES, "two"));

        seal(p);
        assertEquals(ScoringGateState.COLLECTING, gateOf(p).getState());
        assertEquals(2, gateOf(p).getPendingCount());
        assertEquals(0, readyEvents(p));

        deliver(finishJob(jobId(s1, "LEETCODE"), true, "OK"));
        assertEquals(ScoringGateState.COLLECTING, gateOf(p).getState());
        assertEquals(0, readyEvents(p));

        deliver(finishJob(jobId(s2, "CODEFORCES"), true, "OK"));
        DriveScoringGate gate = gateOf(p);
        assertEquals(ScoringGateState.READY, gate.getState());
        assertEquals(2, gate.getPoolSize());
        assertEquals(2, gate.getCapturedCount());
        assertNotNull(gate.getReadyEventId());
        assertEquals(1, readyEvents(p));
    }

    @Test
    @DisplayName("every scrape finished before the deadline: READY in the seal transaction itself")
    void readyAtSealWhenNothingIsPending() {
        JobPosting p = aPosting(ShortlistingMode.COMPOSITE_SCORE);
        User s = applied(p, Map.of(CodingPlatform.LEETCODE, "early"));
        deliver(finishJob(jobId(s, "LEETCODE"), true, "OK"));
        assertTrue(gateRepository.findByPostingId(p.getId()).isEmpty(), "no gate before the seal");

        seal(p);

        assertEquals(ScoringGateState.READY, gateOf(p).getState());
        assertEquals(1, readyEvents(p));
    }

    @Test
    @DisplayName("a drive whose applicants list no coding platforms is READY at seal")
    void readyAtSealWithNoCaptures() {
        JobPosting p = aPosting(ShortlistingMode.COMPOSITE_SCORE);
        applied(p, Map.of());

        seal(p);

        assertEquals(ScoringGateState.READY, gateOf(p).getState());
        assertEquals(1, readyEvents(p));
    }

    @Test
    @DisplayName("a redelivered completion event changes nothing and never readies a drive twice")
    void duplicateEventsAreHarmless() {
        JobPosting p = aPosting(ShortlistingMode.COMPOSITE_SCORE);
        User s = applied(p, Map.of(CodingPlatform.LEETCODE, "dup"));
        seal(p);

        CodingScrapeCompletedEvent event = finishJob(jobId(s, "LEETCODE"), true, "OK");
        deliver(event);
        deliver(event);
        deliver(event);

        assertEquals(ScoringGateState.READY, gateOf(p).getState());
        assertEquals(1, readyEvents(p));
    }

    @Test
    @DisplayName("a failed scrape holds the drive for TnP review instead of scoring or waiting")
    void failureHoldsForReviewAndDisqualificationReleases() {
        JobPosting p = aPosting(ShortlistingMode.COMPOSITE_SCORE);
        User ok = applied(p, Map.of(CodingPlatform.LEETCODE, "fine"));
        User bad = applied(p, Map.of(CodingPlatform.CODECHEF, "missing_user"));
        seal(p);

        deliver(finishJob(jobId(ok, "LEETCODE"), true, "OK"));
        deliver(finishJob(jobId(bad, "CODECHEF"), false, "HANDLE_NOT_FOUND"));

        DriveScoringGate gate = gateOf(p);
        assertEquals(ScoringGateState.AWAITING_TNP_REVIEW, gate.getState());
        assertEquals(1, gate.getReviewCount());
        assertNotNull(gate.getReviewRequestedAt());
        assertEquals(0, readyEvents(p), "the engine is not told to start while a student awaits review");

        // The TnP removes the flagged student from the pool. The reconciler (or the
        // review action, once built) recounts and the drive becomes ready.
        JobApplication app = applicationRepository
                .findByPosting_IdAndStudent_IdAndIsDeletedFalse(p.getId(), bad.getId()).orElseThrow();
        app.setStatus(ApplicationStatus.DISQUALIFIED);
        applicationRepository.save(app);
        gateService.reconcile(p.getId());

        assertEquals(ScoringGateState.READY, gateOf(p).getState());
        assertEquals(1, readyEvents(p));
    }

    @Test
    @DisplayName("the reconciler settles a capture whose completion event was lost")
    void reconcilerRepairsALostEvent() {
        JobPosting p = aPosting(ShortlistingMode.COMPOSITE_SCORE);
        User s = applied(p, Map.of(CodingPlatform.LEETCODE, "lost_event"));
        seal(p);

        finishJob(jobId(s, "LEETCODE"), true, "OK");   // the row changes; the event never arrives
        assertEquals(ScoringGateState.COLLECTING, gateOf(p).getState());

        gateService.reconcile(p.getId());

        ApplicationCodingCapture c = capturesOf(p).get(0);
        assertEquals(CaptureState.CAPTURED, c.getState());
        assertNotNull(c.getSnapshotId());
        assertEquals(ScoringGateState.READY, gateOf(p).getState());
        assertEquals(1, readyEvents(p));
    }

    @Test
    @DisplayName("a cancelled drive's gate is closed, never readied")
    void cancelledDriveNeverReadies() {
        JobPosting p = aPosting(ShortlistingMode.COMPOSITE_SCORE);
        User s = applied(p, Map.of(CodingPlatform.LEETCODE, "cancelled"));
        seal(p);

        JobPosting fresh = postingRepository.findById(p.getId()).orElseThrow();
        fresh.setStatus(PostingStatus.CANCELLED);
        postingRepository.save(fresh);
        deliver(finishJob(jobId(s, "LEETCODE"), true, "OK"));

        assertEquals(ScoringGateState.CANCELLED, gateOf(p).getState());
        assertEquals(0, readyEvents(p));
    }

    /**
     * The race the gate's lock order exists for: the deadline seal and the last
     * scrape results committing at the same instant. Whatever the interleaving,
     * the drive must end READY with exactly one event -- never stuck in
     * COLLECTING because each side missed the other, and never readied twice.
     */
    @Test
    @DisplayName("seal racing the last scrape completions still readies exactly once")
    void sealRacingCompletionsReadiesExactlyOnce() throws Exception {
        for (int round = 0; round < 25; round++) {
            JobPosting p = aPosting(ShortlistingMode.COMPOSITE_SCORE);
            User a = applied(p, Map.of(CodingPlatform.LEETCODE, "race_a" + round));
            User b = applied(p, Map.of(CodingPlatform.CODECHEF, "race_b" + round));
            CodingScrapeCompletedEvent ea = finishJob(jobId(a, "LEETCODE"), true, "OK");
            CodingScrapeCompletedEvent eb = finishJob(jobId(b, "CODECHEF"), true, "OK");

            JobPosting fresh = postingRepository.findById(p.getId()).orElseThrow();
            fresh.setApplicationDeadline(Instant.now().minusSeconds(1));
            postingRepository.save(fresh);

            CountDownLatch go = new CountDownLatch(1);
            List<CompletableFuture<Void>> racers = new ArrayList<>();
            racers.add(CompletableFuture.runAsync(() -> { await(go); sealingService.seal(p.getId()); }));
            racers.add(CompletableFuture.runAsync(() -> { await(go); deliver(ea); }));
            racers.add(CompletableFuture.runAsync(() -> { await(go); deliver(eb); }));
            go.countDown();
            CompletableFuture.allOf(racers.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);

            // Whichever finished last saw everything; if the seal went first and
            // both completions found the gate, it is already READY. If a completion
            // ran before the gate existed, the seal counted its capture as settled.
            assertEquals(ScoringGateState.READY, gateOf(p).getState(), "round " + round);
            assertEquals(1, readyEvents(p), "round " + round);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------ TnP review

    /** A sealed drive with one clean student and the given number of flagged ones. */
    private record Reviewed(JobPosting posting, List<User> flagged) {}

    private Reviewed sealedWithFlagged(int flaggedCount, Integer seats) {
        JobPosting p = aPosting(ShortlistingMode.COMPOSITE_SCORE);
        if (seats != null) {
            JobPosting fresh = postingRepository.findById(p.getId()).orElseThrow();
            fresh.setMaxApplications(seats);
            p = postingRepository.save(fresh);
        }
        User clean = applied(p, Map.of(CodingPlatform.LEETCODE, "clean"));
        List<User> flagged = new ArrayList<>();
        for (int i = 0; i < flaggedCount; i++) {
            flagged.add(applied(p, Map.of(CodingPlatform.CODECHEF, "missing" + i)));
        }
        seal(p);
        deliver(finishJob(jobId(clean, "LEETCODE"), true, "OK"));
        for (User f : flagged) {
            deliver(finishJob(jobId(f, "CODECHEF"), false, "HANDLE_NOT_FOUND"));
        }
        assertEquals(ScoringGateState.AWAITING_TNP_REVIEW, gateOf(p).getState());
        return new Reviewed(p, flagged);
    }

    private Long applicationOf(JobPosting p, User s) {
        return applicationRepository.findByPosting_IdAndStudent_IdAndIsDeletedFalse(p.getId(), s.getId())
                .orElseThrow().getId();
    }

    private Map<String, Object> readyPayload(JobPosting p) {
        return jdbc.queryForMap("""
                SELECT payload->'payload'->>'directlyShortlisted' AS direct,
                       payload->'payload'->>'removedByReview'     AS removed,
                       payload->'payload'->>'engineSeats'         AS seats,
                       payload->'payload'->>'shortlistTarget'     AS target
                  FROM outbox_events WHERE topic = ? AND aggregate_id = ?
                """, DriveTopics.DRIVE_READY_FOR_SCORING, String.valueOf(p.getId()));
    }

    @Test
    @DisplayName("the review screen shows what failed, what was captured, earlier data and the student's record")
    void reviewScreenShowsTheWholeRecord() {
        JobPosting p = aPosting(ShortlistingMode.COMPOSITE_SCORE);
        User s = applied(p, Map.of(CodingPlatform.LEETCODE, "good_lc", CodingPlatform.CODECHEF, "typo_cc"));

        Long profileId = profileRepository.findByUserIdAndUser_IsDeletedFalse(s.getId()).orElseThrow().getId();
        jdbc.update("INSERT INTO student_projects (title, technologies, project_link, description, student_profile_id) "
                + "VALUES ('Placement bot', 'Java, Kafka', 'https://example.test/bot', 'A bot', ?)", profileId);
        // Data from an earlier read -- say, the profile-save scrape last month.
        jdbc.update("INSERT INTO coding_profile_snapshots (student_user_id, platform, handle, status, scraped_at, metrics) "
                + "VALUES (?, 'CODECHEF', 'old_cc', 'OK', now() - interval '30 days', "
                + "'{\"rating\": 1612, \"stars\": 3, \"solved_total\": 140, \"languages\": []}'::jsonb)", s.getId());

        seal(p);
        deliver(finishJob(jobId(s, "LEETCODE"), true, "OK"));
        deliver(finishJob(jobId(s, "CODECHEF"), false, "HANDLE_NOT_FOUND"));

        CodingReviewResponse r = reviewService.review(TRUST_WIDE, p.getId());
        assertEquals("AWAITING_TNP_REVIEW", r.gateState());
        assertEquals(1, r.awaitingDecision());
        assertEquals(1, r.items().size());

        CodingReviewResponse.Item item = r.items().get(0);
        assertEquals(2, item.captures().size(), "both platforms, the good one and the failed one");
        assertTrue(item.captures().stream().anyMatch(c -> "HANDLE_NOT_FOUND".equals(c.outcome())
                && "NEEDS_REVIEW".equals(c.reviewState())));
        assertTrue(item.captures().stream().anyMatch(c -> "CAPTURED".equals(c.state())));
        assertEquals(1, item.earlierData().size());
        assertEquals(1612, ((Number) item.earlierData().get(0).metrics().get("rating")).intValue());
        assertFalse(item.earlierData().get(0).metrics().containsKey("languages"), "empty values are dropped");
        assertEquals(2, item.codingProfiles().size());
        assertEquals("Placement bot", item.projects().get(0).title());
        assertNull(item.decision());
    }

    @Test
    @DisplayName("direct picks use seats: 100 seats, 1 pick, 1 removal -> the engine fills 99")
    void decisionsReadyTheDriveAndCountSeats() {
        Reviewed d = sealedWithFlagged(2, 100);
        Long pick = applicationOf(d.posting(), d.flagged().get(0));
        Long drop = applicationOf(d.posting(), d.flagged().get(1));

        reviewService.decide(TRUST_WIDE, d.posting().getId(), new CodingReviewDecisionRequest(
                List.of(pick), CodingReviewDecision.SHORTLIST_DIRECTLY, "Strong projects and 3-star CodeChef history"));
        assertEquals(ScoringGateState.AWAITING_TNP_REVIEW, gateOf(d.posting()).getState(),
                "one flagged student is still undecided");
        assertEquals(0, readyEvents(d.posting()));

        CodingReviewResponse after = reviewService.decide(TRUST_WIDE, d.posting().getId(),
                new CodingReviewDecisionRequest(List.of(drop), CodingReviewDecision.REMOVE,
                        "No verifiable coding record and CGPA below the cohort"));

        assertEquals("READY", after.gateState());
        assertEquals(1, readyEvents(d.posting()));
        Map<String, Object> payload = readyPayload(d.posting());
        assertEquals("100", payload.get("target"));
        assertEquals("1", payload.get("direct"));
        assertEquals("1", payload.get("removed"));
        assertEquals("99", payload.get("seats"));

        ApplicationCodingReview row = reviewRepository.findByApplicationIdIn(List.of(pick)).get(0);
        assertEquals(TRUST_WIDE.userId(), row.getDecidedBy());
        assertEquals("Strong projects and 3-star CodeChef history", row.getReason());
    }

    @Test
    @DisplayName("deciding everyone at once is one request and readies the drive")
    void decideAll() {
        Reviewed d = sealedWithFlagged(3, null);
        List<Long> all = d.flagged().stream().map(u -> applicationOf(d.posting(), u)).toList();

        CodingReviewResponse r = reviewService.decide(TRUST_WIDE, d.posting().getId(),
                new CodingReviewDecisionRequest(all, CodingReviewDecision.REMOVE, "Handles could not be verified"));

        assertEquals("READY", r.gateState());
        assertEquals(1, readyEvents(d.posting()));
        assertNull(readyPayload(d.posting()).get("seats"), "no target set, so no seat figure");
    }

    @Test
    @DisplayName("a decision can be changed while the review is open, and is final once the drive is ready")
    void decisionsChangeUntilReadyThenFreeze() {
        Reviewed d = sealedWithFlagged(2, null);
        Long a = applicationOf(d.posting(), d.flagged().get(0));
        Long b = applicationOf(d.posting(), d.flagged().get(1));

        reviewService.decide(TRUST_WIDE, d.posting().getId(),
                new CodingReviewDecisionRequest(List.of(a), CodingReviewDecision.REMOVE, "first look"));
        reviewService.decide(TRUST_WIDE, d.posting().getId(),
                new CodingReviewDecisionRequest(List.of(a), CodingReviewDecision.SHORTLIST_DIRECTLY, "second look"));
        List<ApplicationCodingReview> rows = reviewRepository.findByApplicationIdIn(List.of(a));
        assertEquals(1, rows.size());
        assertEquals(CodingReviewDecision.SHORTLIST_DIRECTLY, rows.get(0).getDecision());

        reviewService.decide(TRUST_WIDE, d.posting().getId(),
                new CodingReviewDecisionRequest(List.of(b), CodingReviewDecision.REMOVE, "no record"));
        assertEquals(ScoringGateState.READY, gateOf(d.posting()).getState());

        ApiException closed = assertThrows(ApiException.class, () -> reviewService.decide(TRUST_WIDE,
                d.posting().getId(), new CodingReviewDecisionRequest(List.of(a), CodingReviewDecision.REMOVE, "late")));
        assertEquals("CODING_REVIEW_CLOSED", closed.getCode());
        assertEquals(1, readyEvents(d.posting()));
    }

    @Test
    @DisplayName("only flagged applications can be decided")
    void unflaggedApplicationsAreRefused() {
        JobPosting p = aPosting(ShortlistingMode.COMPOSITE_SCORE);
        User clean = applied(p, Map.of(CodingPlatform.LEETCODE, "fine"));
        User bad = applied(p, Map.of(CodingPlatform.CODECHEF, "gone"));
        seal(p);
        deliver(finishJob(jobId(bad, "CODECHEF"), false, "HANDLE_NOT_FOUND"));

        assertThrows(ApiException.class, () -> reviewService.decide(TRUST_WIDE, p.getId(),
                new CodingReviewDecisionRequest(List.of(applicationOf(p, clean)),
                        CodingReviewDecision.SHORTLIST_DIRECTLY, "sneaking a clean student in")));
        assertTrue(reviewRepository.findByPostingId(p.getId()).isEmpty());
    }

    @Test
    @DisplayName("a coordinator from another college can neither open nor decide the review")
    void outOfScopeReviewerIsRefused() {
        Reviewed d = sealedWithFlagged(1, null);
        Long a = applicationOf(d.posting(), d.flagged().get(0));

        ApiException read = assertThrows(ApiException.class,
                () -> reviewService.review(OTHER_COLLEGE_COORDINATOR, d.posting().getId()));
        assertEquals("SCOPE_VIOLATION", read.getCode());
        assertThrows(ApiException.class, () -> reviewService.decide(OTHER_COLLEGE_COORDINATOR, d.posting().getId(),
                new CodingReviewDecisionRequest(List.of(a), CodingReviewDecision.REMOVE, "not mine")));
        assertTrue(reviewRepository.findByPostingId(d.posting().getId()).isEmpty());
    }


    // ------------------------------------------------------- student re-check

    /** Notices of this template addressed to this student, in the outbox. */
    private int notices(User s, String templateKey) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM outbox_events
                 WHERE topic = ?
                   AND payload->'payload'->>'templateKey' = ?
                   AND payload->'payload'->'recipients'->0->>'userId' = ?
                """, Integer.class, DriveTopics.NOTIFICATION_DISPATCH, templateKey, String.valueOf(s.getId()));
        return n == null ? 0 : n;
    }

    private String profileHandle(User s, CodingPlatform platform) {
        return jdbc.queryForObject("""
                SELECT cp.username FROM student_coding_profiles cp
                  JOIN student_profiles sp ON sp.id = cp.student_profile_id
                 WHERE sp.user_id = ? AND cp.platform = ?
                """, String.class, s.getId(), platform.name());
    }

    private ApplicationCodingCapture captureOf(JobPosting p, CodingPlatform platform) {
        return capturesOf(p).stream().filter(c -> c.getPlatform() == platform
                && c.getState() != CaptureState.SUPERSEDED).findFirst().orElseThrow();
    }

    /** The newest job for this platform -- after a re-check, the re-check's job. */
    private long latestJobId(User s, String platform) {
        return jobsOf(s).stream().filter(j -> platform.equals(j.get("platform")))
                .map(j -> ((Number) j.get("id")).longValue()).reduce((a, b) -> b).orElseThrow();
    }

    @Test
    @DisplayName("a handle not found before the deadline goes to the student, not the TnP")
    void handleNotFoundBeforeDeadlineAsksTheStudent() {
        JobPosting p = aPosting(ShortlistingMode.COMPOSITE_SCORE);
        User s = applied(p, Map.of(CodingPlatform.LEETCODE, "typo_lc"));
        deliver(finishJob(jobId(s, "LEETCODE"), false, "HANDLE_NOT_FOUND"));

        ApplicationCodingCapture c = captureOf(p, CodingPlatform.LEETCODE);
        assertEquals(CaptureState.AWAITING_STUDENT, c.getState());
        assertEquals(CaptureReviewState.NOT_REQUIRED, c.getReviewState());
        assertEquals(1, notices(s, NotificationTemplates.CODING_HANDLE_CHECK_NEEDED));

        CodingCheckResponse checks = recheckService.myChecks(s.getId());
        assertEquals(1, checks.items().size());
        CodingCheckResponse.Item item = checks.items().get(0);
        assertEquals("NEEDS_RECHECK", item.status());
        assertTrue(item.canRecheck());
        assertEquals("typo_lc", item.handle());
        assertEquals(1, item.drives().size());
    }

    @Test
    @DisplayName("a corrected re-check updates the profile, reads again, and the drive needs no review")
    void correctedRecheckCaptures() {
        JobPosting p = aPosting(ShortlistingMode.COMPOSITE_SCORE);
        User s = applied(p, Map.of(CodingPlatform.LEETCODE, "typo_lc"));
        deliver(finishJob(jobId(s, "LEETCODE"), false, "HANDLE_NOT_FOUND"));

        CodingCheckResponse after = recheckService.recheck(s.getId(), CodingPlatform.LEETCODE, "  real_lc ");

        assertEquals("real_lc", profileHandle(s, CodingPlatform.LEETCODE), "fixed once, on the profile");
        ApplicationCodingCapture c = captureOf(p, CodingPlatform.LEETCODE);
        assertEquals(CaptureState.PENDING, c.getState());
        assertEquals(1, c.getRecheckCount());
        assertEquals("typo_lc", c.getPreviousHandle());
        assertEquals("real_lc", c.getHandle());
        Map<String, Object> job = jobsOf(s).get(jobsOf(s).size() - 1);
        assertEquals("RECHECK", job.get("reason"));
        assertEquals("real_lc", job.get("handle"));
        assertEquals("CHECKING", after.items().get(0).status());

        deliver(finishJob(latestJobId(s, "LEETCODE"), true, "OK"));
        assertEquals(CaptureState.CAPTURED, captureOf(p, CodingPlatform.LEETCODE).getState());
        assertEquals("CONFIRMED", recheckService.myChecks(s.getId()).items().get(0).status());

        seal(p);
        assertEquals(ScoringGateState.READY, gateOf(p).getState());
        assertEquals(1, readyEvents(p));
    }

    @Test
    @DisplayName("a re-check that fails on the handle again: scored on the other platforms, no TnP flag")
    void failedRecheckScoresOnOthers() {
        JobPosting p = aPosting(ShortlistingMode.COMPOSITE_SCORE);
        User s = applied(p, Map.of(CodingPlatform.LEETCODE, "typo_lc", CodingPlatform.CODEFORCES, "good_cf"));
        deliver(finishJob(jobId(s, "CODEFORCES"), true, "OK"));
        deliver(finishJob(jobId(s, "LEETCODE"), false, "HANDLE_NOT_FOUND"));

        recheckService.recheck(s.getId(), CodingPlatform.LEETCODE, "typo_lc");
        deliver(finishJob(latestJobId(s, "LEETCODE"), false, "HANDLE_NOT_FOUND"));

        ApplicationCodingCapture c = captureOf(p, CodingPlatform.LEETCODE);
        assertEquals(CaptureState.FAILED, c.getState());
        assertEquals(CaptureReviewState.NOT_REQUIRED, c.getReviewState());
        assertEquals(1, notices(s, NotificationTemplates.CODING_HANDLE_STILL_FAILING));
        assertEquals("SCORED_ON_OTHERS", recheckService.myChecks(s.getId()).items().get(0).status());

        seal(p);
        assertEquals(ScoringGateState.READY, gateOf(p).getState(), "the student is scored on Codeforces");
        assertEquals(0, notices(s, NotificationTemplates.CODING_CAPTURE_FLAGGED));
    }

    @Test
    @DisplayName("a failed re-check with no other platform captured goes to the TnP as NOTHING_LEFT")
    void failedRecheckWithNothingLeftIsFlagged() {
        JobPosting p = aPosting(ShortlistingMode.COMPOSITE_SCORE);
        User s = applied(p, Map.of(CodingPlatform.LEETCODE, "typo_lc"));
        deliver(finishJob(jobId(s, "LEETCODE"), false, "HANDLE_NOT_FOUND"));
        recheckService.recheck(s.getId(), CodingPlatform.LEETCODE, "still_typo");
        deliver(finishJob(latestJobId(s, "LEETCODE"), false, "HANDLE_NOT_FOUND"));

        seal(p);

        ApplicationCodingCapture c = captureOf(p, CodingPlatform.LEETCODE);
        assertEquals(CaptureReviewState.NEEDS_REVIEW, c.getReviewState());
        assertEquals(CaptureFlagReason.NOTHING_LEFT, c.getFlagReason());
        assertEquals(ScoringGateState.AWAITING_TNP_REVIEW, gateOf(p).getState());
        assertEquals(1, notices(s, NotificationTemplates.CODING_CAPTURE_FLAGGED));
    }

    @Test
    @DisplayName("not re-checked by the deadline: the seal flags it NO_RECHECK and tells the student")
    void noRecheckByDeadlineIsFlagged() {
        JobPosting p = aPosting(ShortlistingMode.COMPOSITE_SCORE);
        User s = applied(p, Map.of(CodingPlatform.LEETCODE, "typo_lc", CodingPlatform.CODEFORCES, "good_cf"));
        deliver(finishJob(jobId(s, "CODEFORCES"), true, "OK"));
        deliver(finishJob(jobId(s, "LEETCODE"), false, "HANDLE_NOT_FOUND"));

        seal(p);

        ApplicationCodingCapture c = captureOf(p, CodingPlatform.LEETCODE);
        assertEquals(CaptureState.FAILED, c.getState());
        assertEquals(CaptureFlagReason.NO_RECHECK, c.getFlagReason());
        assertEquals(ScoringGateState.AWAITING_TNP_REVIEW, gateOf(p).getState(),
                "flagged even though Codeforces was captured");
        assertEquals(1, notices(s, NotificationTemplates.CODING_CAPTURE_FLAGGED));

        ApiException late = assertThrows(ApiException.class,
                () -> recheckService.recheck(s.getId(), CodingPlatform.LEETCODE, "real_lc"));
        assertEquals("CODING_RECHECK_UNAVAILABLE", late.getCode());

        CodingReviewResponse r = reviewService.review(TRUST_WIDE, p.getId());
        assertTrue(r.items().get(0).captures().stream().anyMatch(v -> "NO_RECHECK".equals(v.flagReason())));
    }

    @Test
    @DisplayName("a platform outage is the TnP's, at once, with a server-error notice to the student")
    void platformErrorIsFlaggedAtOnce() {
        JobPosting p = aPosting(ShortlistingMode.COMPOSITE_SCORE);
        User s = applied(p, Map.of(CodingPlatform.CODECHEF, "good_cc", CodingPlatform.CODEFORCES, "good_cf"));
        deliver(finishJob(jobId(s, "CODEFORCES"), true, "OK"));
        deliver(finishJob(jobId(s, "CODECHEF"), false, "PLATFORM_ERROR"));

        ApplicationCodingCapture c = captureOf(p, CodingPlatform.CODECHEF);
        assertEquals(CaptureState.FAILED, c.getState());
        assertEquals(CaptureFlagReason.SERVER_ERROR, c.getFlagReason());
        assertEquals(1, notices(s, NotificationTemplates.CODING_CAPTURE_FLAGGED));
        assertEquals(0, notices(s, NotificationTemplates.CODING_HANDLE_CHECK_NEEDED), "nothing for them to fix");
        assertThrows(ApiException.class, () -> recheckService.recheck(s.getId(), CodingPlatform.CODECHEF, "good_cc"));

        seal(p);
        assertEquals(ScoringGateState.AWAITING_TNP_REVIEW, gateOf(p).getState());
    }

    @Test
    @DisplayName("a handle that fails after the deadline goes to the TnP as AFTER_DEADLINE")
    void handleFailingAfterDeadlineIsFlagged() {
        JobPosting p = aPosting(ShortlistingMode.COMPOSITE_SCORE);
        User s = applied(p, Map.of(CodingPlatform.LEETCODE, "late_typo"));
        seal(p);
        deliver(finishJob(jobId(s, "LEETCODE"), false, "HANDLE_NOT_FOUND"));

        assertEquals(CaptureFlagReason.AFTER_DEADLINE, captureOf(p, CodingPlatform.LEETCODE).getFlagReason());
        assertEquals(1, notices(s, NotificationTemplates.CODING_CAPTURE_FLAGGED));
        assertEquals(0, notices(s, NotificationTemplates.CODING_HANDLE_CHECK_NEEDED));
    }

    @Test
    @DisplayName("one re-check per platform: a second is refused")
    void onlyOneRecheck() {
        JobPosting p = aPosting(ShortlistingMode.COMPOSITE_SCORE);
        User s = applied(p, Map.of(CodingPlatform.LEETCODE, "typo_lc"));
        deliver(finishJob(jobId(s, "LEETCODE"), false, "HANDLE_NOT_FOUND"));
        recheckService.recheck(s.getId(), CodingPlatform.LEETCODE, "try_one");

        ApiException again = assertThrows(ApiException.class,
                () -> recheckService.recheck(s.getId(), CodingPlatform.LEETCODE, "try_two"));
        assertEquals("CODING_RECHECK_UNAVAILABLE", again.getCode());
        assertEquals("try_one", profileHandle(s, CodingPlatform.LEETCODE), "the refused attempt changed nothing");
    }

    @Test
    @DisplayName("fix once, applies everywhere: one re-check re-reads every open drive with one job")
    void recheckAppliesToEveryOpenDrive() {
        JobPosting a = aPosting(ShortlistingMode.COMPOSITE_SCORE);
        JobPosting b = aPosting(ShortlistingMode.COMPOSITE_SCORE);
        User s = aStudent(Map.of(CodingPlatform.LEETCODE, "typo_lc"));
        makeEligible(a, s);
        makeEligible(b, s);
        applicationService.apply(a.getId(), s.getId());
        applicationService.apply(b.getId(), s.getId());
        deliver(finishJob(jobId(s, "LEETCODE"), false, "HANDLE_NOT_FOUND"));
        assertEquals(1, notices(s, NotificationTemplates.CODING_HANDLE_CHECK_NEEDED), "one notice for both drives");

        recheckService.recheck(s.getId(), CodingPlatform.LEETCODE, "real_lc");

        long recheckJob = latestJobId(s, "LEETCODE");
        assertEquals(recheckJob, captureOf(a, CodingPlatform.LEETCODE).getJobId());
        assertEquals(recheckJob, captureOf(b, CodingPlatform.LEETCODE).getJobId());
        deliver(finishJob(recheckJob, true, "OK"));
        assertEquals(CaptureState.CAPTURED, captureOf(a, CodingPlatform.LEETCODE).getState());
        assertEquals(CaptureState.CAPTURED, captureOf(b, CodingPlatform.LEETCODE).getState());
    }

    // ----------------------------------------------------------- the contract

    /**
     * The two payloads below were written by the real Python scraper on
     * 2026-10-15. If either side changes a field name, this fails here rather than
     * as a silently ignored field on a deadline evening.
     */
    @Test
    @DisplayName("events written by the Python scraper parse into the Java contract")
    void pythonPayloadsParse() throws Exception {
        TypeReference<EventEnvelope<CodingScrapeCompletedEvent>> type = new TypeReference<>() {};

        EventEnvelope<CodingScrapeCompletedEvent> captured = objectMapper.readValue(
                new ClassPathResource("events/coding-scrape-completed-captured.json")
                        .getContentAsString(StandardCharsets.UTF_8), type);
        assertEquals("CodingScrapeCompleted", captured.eventType());
        assertEquals("CODECHEF", captured.payload().platform());
        assertEquals("DONE", captured.payload().jobStatus());
        assertEquals(5L, captured.payload().snapshotId());
        assertNotNull(captured.payload().finishedAt());
        assertTrue(captured.payload().captured());

        EventEnvelope<CodingScrapeCompletedEvent> notFound = objectMapper.readValue(
                new ClassPathResource("events/coding-scrape-completed-not-found.json")
                        .getContentAsString(StandardCharsets.UTF_8), type);
        assertEquals("HANDLE_NOT_FOUND", notFound.payload().outcome());
        assertNull(notFound.payload().snapshotId());
        assertFalse(notFound.payload().captured());
    }
}
