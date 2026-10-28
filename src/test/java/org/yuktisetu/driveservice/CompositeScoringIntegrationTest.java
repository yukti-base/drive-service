package org.yuktisetu.driveservice;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.yuktisetu.core.exception.ApiException;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.drive.db.ApplicationScore;
import org.yuktisetu.drive.db.Company;
import org.yuktisetu.drive.db.DriveScoreRun;
import org.yuktisetu.drive.db.EligibilityRun;
import org.yuktisetu.drive.db.JobPosting;
import org.yuktisetu.drive.db.PlatformSettings;
import org.yuktisetu.drive.db.PostingEligibilityCriteria;
import org.yuktisetu.drive.db.PostingEligibleStudent;
import org.yuktisetu.drive.db.PostingReapplicationPolicy;
import org.yuktisetu.drive.db.PostingScoringConfig;
import org.yuktisetu.drive.model.ApplicationStatus;
import org.yuktisetu.drive.model.CompanyStatus;
import org.yuktisetu.drive.model.EligibilityRunReason;
import org.yuktisetu.drive.model.EligibilityRunStatus;
import org.yuktisetu.drive.model.JobType;
import org.yuktisetu.drive.model.PostingStatus;
import org.yuktisetu.drive.model.ScoreRunStatus;
import org.yuktisetu.drive.model.ScoringFactor;
import org.yuktisetu.drive.model.ScoringGateState;
import org.yuktisetu.drive.model.ShortlistingMode;
import org.yuktisetu.drive.repository.ApplicationScoreRepository;
import org.yuktisetu.drive.repository.CompanyRepository;
import org.yuktisetu.drive.repository.DriveScoreRunRepository;
import org.yuktisetu.drive.repository.DriveScoringGateRepository;
import org.yuktisetu.drive.repository.EligibilityRunRepository;
import org.yuktisetu.drive.repository.JobApplicationRepository;
import org.yuktisetu.drive.repository.JobPostingRepository;
import org.yuktisetu.drive.repository.PlatformSettingsRepository;
import org.yuktisetu.drive.repository.PostingEligibilityCriteriaRepository;
import org.yuktisetu.drive.repository.PostingEligibleStudentRepository;
import org.yuktisetu.drive.repository.PostingReapplicationPolicyRepository;
import org.yuktisetu.drive.repository.PostingScoringConfigRepository;
import org.yuktisetu.driveservice.dto.ScoreResponses.StaffDriveScores;
import org.yuktisetu.driveservice.dto.ScoreResponses.StudentScoresResponse;
import org.yuktisetu.driveservice.service.ApplicationService;
import org.yuktisetu.driveservice.service.DeadlineSealingService;
import org.yuktisetu.driveservice.service.ScoringGateService;
import org.yuktisetu.driveservice.service.ScoringRunService;
import org.yuktisetu.driveservice.service.ShortlistService;
import org.yuktisetu.driveservice.service.StudentScoreService;
import org.yuktisetu.identity.db.User;
import org.yuktisetu.identity.model.UserStatus;
import org.yuktisetu.identity.repository.UserRepository;
import org.yuktisetu.messaging.events.CodingScrapeCompletedEvent;
import org.yuktisetu.messaging.events.NotificationTemplates;
import org.yuktisetu.profile.db.CodingProfile;
import org.yuktisetu.profile.db.StudentProfile;
import org.yuktisetu.profile.model.CodingPlatform;
import org.yuktisetu.profile.repository.StudentProfileRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scoring engine end to end: a sealed, READY drive becomes stored scores;
 * the TnP publishes a shortlist; a student sees their breakdown only behind the
 * platform switch, and never their rank.
 *
 * The scraper is simulated as in CodingCaptureAndScoringGateIntegrationTest: a
 * finished job is a row update plus a snapshot row, then the completion event.
 * Not @Transactional; runs against yuktisetu_test (create-drop).
 */
@SpringBootTest
@ActiveProfiles("test")
class CompositeScoringIntegrationTest {

    @Autowired ApplicationService applicationService;
    @Autowired DeadlineSealingService sealingService;
    @Autowired ScoringGateService gateService;
    @Autowired ScoringRunService scoringRunService;
    @Autowired ShortlistService shortlistService;
    @Autowired StudentScoreService studentScoreService;
    @Autowired org.yuktisetu.driveservice.service.DriveReopenService reopenService;
    @Autowired JobPostingRepository postingRepository;
    @Autowired JobApplicationRepository applicationRepository;
    @Autowired DriveScoringGateRepository gateRepository;
    @Autowired DriveScoreRunRepository runRepository;
    @Autowired ApplicationScoreRepository scoreRepository;
    @Autowired PostingScoringConfigRepository configRepository;
    @Autowired PlatformSettingsRepository settingsRepository;
    @Autowired CompanyRepository companyRepository;
    @Autowired PostingEligibilityCriteriaRepository criteriaRepository;
    @Autowired PostingReapplicationPolicyRepository policyRepository;
    @Autowired PostingEligibleStudentRepository eligibleStudentRepository;
    @Autowired EligibilityRunRepository eligibilityRunRepository;
    @Autowired UserRepository userRepository;
    @Autowired StudentProfileRepository profileRepository;
    @Autowired JdbcTemplate jdbc;

    private static final AtomicLong SEQ = new AtomicLong(700000);

    private static final UserPrincipal TNP = new UserPrincipal(1L, "tnp@example.test",
            List.of(new UserPrincipal.RoleClaim("TNP_SUPER_ADMIN", null, null)));

    @BeforeEach
    void schemaThatOnlyTheMigrationCreates() {
        jdbc.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS uk_scrape_job_pending
                    ON coding_scrape_jobs (student_user_id, platform)
                    WHERE status IN ('PENDING','RUNNING')
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS coding_profile_snapshots (
                    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    student_user_id bigint NOT NULL, platform varchar(32) NOT NULL,
                    handle varchar(128) NOT NULL, status varchar(24) NOT NULL,
                    scraped_at timestamptz NOT NULL, extraction_tier varchar(24),
                    raw jsonb, metrics jsonb, last_error text, job_id bigint)
                """);
        setVisible(false);
    }

    /**
     * The snapshots table is created by hand, so create-drop never empties it,
     * while user ids restart on every run. Rows left behind would be read as an
     * earlier scrape of a different student next time -- so remove ours.
     */
    @AfterEach
    void removeOurSnapshots() {
        jdbc.update("DELETE FROM coding_profile_snapshots WHERE handle LIKE 'score\\_%'");
    }

    // ---------------------------------------------------------------- seeding

    private record Applicant(User user, double cgpa, Integer rating) {}

    private User aStudent(double cgpa, Map<CodingPlatform, String> handles) {
        long n = SEQ.incrementAndGet();
        Date now = new Date();
        User u = userRepository.save(User.builder()
                .email("score" + n + "@example.test").phone("71" + n).password("x")
                .firstName("Score").lastName("Student" + n)
                .status(UserStatus.ACTIVE)
                .createdAt(now).updatedAt(now).isDeleted(false)
                .build());
        StudentProfile profile = StudentProfile.builder()
                .user(u).graduationYear(2027).cgpa(cgpa)
                .activeBacklogs(0).totalBacklogs(0)
                .createdAt(now).updatedAt(now)
                .build();
        handles.forEach((platform, handle) -> profile.getCodingProfiles().add(
                CodingProfile.builder().studentProfile(profile).platform(platform).username(handle).build()));
        profileRepository.save(profile);
        return u;
    }

    private JobPosting aPosting(BigDecimal academic, BigDecimal coding, BigDecimal threshold, Integer seats) {
        Company c = new Company();
        c.setName("Score Co");
        c.setCode("SC-" + UUID.randomUUID().toString().substring(0, 8));
        c.setStatus(CompanyStatus.ACTIVE);
        c = companyRepository.save(c);

        JobPosting p = new JobPosting();
        p.setCompany(c);
        p.setPostingCode("SP-" + UUID.randomUUID().toString().substring(0, 8));
        p.setTitle("Software Engineer");
        p.setJobType(JobType.FULL_TIME);
        p.setStatus(PostingStatus.PUBLISHED);
        p.setApplicationDeadline(Instant.now().plusSeconds(7200));
        p.setAcademicYear("2026-27");
        p.setShortlistingMode(ShortlistingMode.COMPOSITE_SCORE);
        p.setMaxApplications(seats);
        p = postingRepository.save(p);

        PostingEligibilityCriteria crit = new PostingEligibilityCriteria();
        crit.setPosting(p);
        criteriaRepository.save(crit);
        PostingReapplicationPolicy pol = new PostingReapplicationPolicy();
        pol.setPosting(p);
        policyRepository.save(pol);

        if (academic != null || coding != null) {
            PostingScoringConfig cfg = new PostingScoringConfig();
            cfg.setPostingId(p.getId());
            cfg.setWeight(ScoringFactor.ACADEMIC_PERFORMANCE, academic);
            cfg.setWeight(ScoringFactor.CODING_PROFICIENCY, coding);
            cfg.setThreshold(threshold);
            configRepository.save(cfg);
        }
        return p;
    }

    private void apply(JobPosting p, User s) {
        EligibilityRun run = new EligibilityRun();
        run.setPosting(p);
        run.setRunReason(EligibilityRunReason.PUBLISH);
        run.setStatus(EligibilityRunStatus.COMPLETED);
        run.setStartedAt(Instant.now());
        run = eligibilityRunRepository.save(run);
        PostingEligibleStudent e = new PostingEligibleStudent();
        e.setPosting(p);
        e.setStudent(s);
        e.setEvaluationRun(run);
        e.setEvaluatedAt(Instant.now());
        eligibleStudentRepository.save(e);
        applicationService.apply(p.getId(), s.getId());
    }

    /** The scraper finishing a student's job on a platform: snapshot, job row, event. */
    private void scrape(User s, String platform, String metricsJson) {
        Map<String, Object> job = jdbc.queryForMap(
                "SELECT id, handle FROM coding_scrape_jobs WHERE student_user_id = ? AND platform = ? "
                        + "AND status = 'PENDING'", s.getId(), platform);
        long jobId = ((Number) job.get("id")).longValue();
        Long snapshotId = jdbc.queryForObject("""
                INSERT INTO coding_profile_snapshots (student_user_id, platform, handle, status, scraped_at, metrics, job_id)
                VALUES (?, ?, ?, 'OK', now(), ?::jsonb, ?) RETURNING id
                """, Long.class, s.getId(), platform, job.get("handle"), metricsJson, jobId);
        jdbc.update("UPDATE coding_scrape_jobs SET status = 'DONE', last_status = 'OK', snapshot_id = ?, "
                + "finished_at = now(), updated_at = now() WHERE id = ?", snapshotId, jobId);
        gateService.onScrapeCompleted(UUID.randomUUID().toString(), new CodingScrapeCompletedEvent(
                jobId, s.getId(), platform, (String) job.get("handle"), "DONE", "OK", snapshotId, 1, null,
                Instant.now()));
    }

    private static String leetcode(int rating) {
        return "{\"rating\": " + rating + ", \"contests_attended\": 10, \"solved_total\": 200}";
    }

    /** n applicants on LeetCode with rising CGPA and rating, every scrape finished. */
    private List<Applicant> pool(JobPosting p, int n) {
        List<Applicant> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double cgpa = 6.0 + i * 0.3;
            int rating = 1400 + i * 50;
            User s = aStudent(cgpa, Map.of(CodingPlatform.LEETCODE, "score_lc_" + SEQ.get()));
            apply(p, s);
            scrape(s, "LEETCODE", leetcode(rating));
            out.add(new Applicant(s, cgpa, rating));
        }
        return out;
    }

    private void sealAndScore(JobPosting p) {
        JobPosting fresh = postingRepository.findById(p.getId()).orElseThrow();
        fresh.setApplicationDeadline(Instant.now().minusSeconds(1));
        postingRepository.save(fresh);
        assertTrue(sealingService.seal(p.getId()));
        assertEquals(ScoringGateState.READY, gateRepository.findByPostingId(p.getId()).orElseThrow().getState());
        // The listener's job, called directly: Kafka is off in tests.
        scoringRunService.run(gateRepository.findByPostingId(p.getId()).orElseThrow().getReadyEventId(), p.getId());
    }

    private DriveScoreRun runOf(JobPosting p) {
        return runRepository.findByPostingIdAndSupersededAtIsNull(p.getId()).orElseThrow();
    }

    private void setVisible(boolean on) {
        PlatformSettings s = settingsRepository.findById(PlatformSettings.SINGLETON_ID).orElseGet(PlatformSettings::new);
        s.setShowScoresToStudents(on);
        settingsRepository.save(s);
    }

    private long applicationIdOf(JobPosting p, User s) {
        return applicationRepository.findByPosting_IdAndStudent_IdAndIsDeletedFalse(p.getId(), s.getId())
                .orElseThrow().getId();
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("a READY drive is scored once: every applicant stored with a rank and breakdown")
    void readyDriveIsScored() {
        JobPosting p = aPosting(BigDecimal.valueOf(60), BigDecimal.valueOf(40), BigDecimal.ZERO, 3);
        List<Applicant> pool = pool(p, 12);
        sealAndScore(p);

        DriveScoreRun run = runOf(p);
        assertEquals(ScoreRunStatus.SCORED, run.getStatus());
        assertEquals(12, run.getPoolSize());
        assertEquals(12, run.getScoredCount());

        List<ApplicationScore> scores = scoreRepository.findByPostingIdOrderByRankAscStudentUserIdAsc(p.getId());
        assertEquals(12, scores.size());
        // The strongest on both factors is the last applicant: rank 1.
        assertEquals(pool.get(11).user().getId(), scores.get(0).getStudentUserId());
        assertEquals(1, scores.get(0).getRank());
        assertTrue(scores.get(0).getComposite().compareTo(scores.get(11).getComposite()) > 0);
        assertTrue(scores.get(0).getBreakdown().contains("CODING_PROFICIENCY"));
        assertTrue(scores.get(0).getBreakdown().contains("LeetCode"));
        // The run keeps the weights it used, as shares of 100.
        assertTrue(run.getConfig().contains("\"share\""));
    }

    @Test
    @DisplayName("a redelivered ready event, or the reconciler racing it, never scores twice")
    void scoringIsIdempotent() {
        JobPosting p = aPosting(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, null);
        pool(p, 10);
        sealAndScore(p);
        Long runId = runOf(p).getId();

        scoringRunService.run(UUID.randomUUID().toString(), p.getId());
        assertEquals(runId, runOf(p).getId());
        assertEquals(10, scoreRepository.countByPostingId(p.getId()));
        assertTrue(scoringRunService.readyWithoutRun().stream()
                .noneMatch(r -> ((Number) r[0]).longValue() == p.getId()));
    }

    @Test
    @DisplayName("a READY drive with no run is found by the reconciler's query")
    void lostEventIsFoundByReconciler() {
        JobPosting p = aPosting(BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, null);
        pool(p, 10);
        JobPosting fresh = postingRepository.findById(p.getId()).orElseThrow();
        fresh.setApplicationDeadline(Instant.now().minusSeconds(1));
        postingRepository.save(fresh);
        assertTrue(sealingService.seal(p.getId()));

        assertTrue(scoringRunService.readyWithoutRun().stream()
                .anyMatch(r -> ((Number) r[0]).longValue() == p.getId()));
    }

    @Test
    @DisplayName("fewer than ten applicants: declined, to be shortlisted by hand")
    void smallPoolIsDeclined() {
        JobPosting p = aPosting(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, null);
        pool(p, 6);
        sealAndScore(p);

        DriveScoreRun run = runOf(p);
        assertEquals(ScoreRunStatus.DECLINED, run.getStatus());
        assertTrue(run.getDeclinedReason().contains("below the minimum of 10"));
        assertEquals(0, scoreRepository.countByPostingId(p.getId()));
    }

    @Test
    @DisplayName("a drive with no weights is declined rather than scored on invented ones")
    void noWeightsIsDeclined() {
        JobPosting p = aPosting(null, null, BigDecimal.ZERO, null);
        pool(p, 10);
        sealAndScore(p);

        assertEquals(ScoreRunStatus.DECLINED, runOf(p).getStatus());
        assertTrue(runOf(p).getDeclinedReason().contains("No scoring weights"));
    }

    @Test
    @DisplayName("a platform too few applicants share holds the run; approval scores everyone")
    void thinPlatformHoldsThenApprovalScores() {
        JobPosting p = aPosting(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, null);
        pool(p, 10);
        User cf = aStudent(9.1, Map.of(CodingPlatform.CODEFORCES, "score_cf_only"));
        apply(p, cf);
        scrape(cf, "CODEFORCES", "{\"rating\": 1900, \"contests_attended\": 20}");
        sealAndScore(p);

        DriveScoreRun run = runOf(p);
        assertEquals(ScoreRunStatus.HELD_FOR_REVIEW, run.getStatus());
        assertEquals(1, run.getHeldCount());
        assertTrue(run.getHeldStudents().contains(String.valueOf(cf.getId())));
        assertEquals(0, scoreRepository.countByPostingId(p.getId()));

        StaffDriveScores screen = shortlistService.approveHeld(TNP, p.getId());
        assertEquals("SCORED", screen.runStatus());
        assertEquals(11, scoreRepository.countByPostingId(p.getId()));
        assertNotNull(runOf(p).getApprovedAt());
    }

    @Test
    @DisplayName("students see nothing until the switch is on AND the shortlist is published; never a rank")
    void studentVisibilityAndShortlistPublish() {
        JobPosting p = aPosting(BigDecimal.valueOf(50), BigDecimal.valueOf(50), BigDecimal.ZERO, 2);
        List<Applicant> pool = pool(p, 10);
        sealAndScore(p);
        User top = pool.get(9).user();
        User bottom = pool.get(0).user();

        // Switch off: nothing, even after publishing.
        StudentScoresResponse off = studentScoreService.myScores(top.getId());
        assertFalse(off.visible());
        assertTrue(off.items().isEmpty());

        // Switch on, shortlist not published: still nothing.
        setVisible(true);
        assertTrue(studentScoreService.myScores(top.getId()).items().isEmpty());

        // The TnP picks the top two ranked applications and publishes.
        StaffDriveScores screen = shortlistService.scores(TNP, p.getId());
        assertEquals("SCORED", screen.runStatus());
        assertTrue(screen.canPublish());
        List<Long> picks = screen.items().subList(0, 2).stream().map(i -> i.applicationId()).toList();
        StaffDriveScores after = shortlistService.publish(TNP, p.getId(), picks);
        assertNotNull(after.shortlistPublishedAt());
        assertFalse(after.canPublish());

        assertEquals(ApplicationStatus.SHORTLISTED, applicationRepository.findById(applicationIdOf(p, top)).orElseThrow().getStatus());
        assertEquals(ApplicationStatus.REJECTED, applicationRepository.findById(applicationIdOf(p, bottom)).orElseThrow().getStatus());
        Integer history = jdbc.queryForObject(
                "SELECT count(*) FROM application_status_history h JOIN job_applications a ON a.id = h.application_id "
                        + "WHERE a.posting_id = ? AND h.to_status IN ('SHORTLISTED','REJECTED')", Integer.class, p.getId());
        assertEquals(10, history);
        Integer shortlistNotices = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_type = 'JobPosting' AND aggregate_id = ? "
                        + "AND payload::text LIKE ?", Integer.class, String.valueOf(p.getId()),
                "%" + NotificationTemplates.SHORTLIST_RESULT + "%");
        assertEquals(2, shortlistNotices); // one shortlisted, one not

        // Now visible: the breakdown and weights, and no rank anywhere.
        StudentScoresResponse on = studentScoreService.myScores(top.getId());
        assertTrue(on.visible());
        assertEquals(1, on.items().size());
        assertEquals(p.getId(), on.items().get(0).postingId());
        assertEquals("SHORTLISTED", on.items().get(0).applicationStatus());
        assertFalse(on.items().get(0).factors().isEmpty());
        assertFalse(on.items().get(0).weights().isEmpty());
        assertFalse(on.toString().contains("rank="));

        // Turning the switch off hides it again.
        setVisible(false);
        assertTrue(studentScoreService.myScores(top.getId()).items().isEmpty());

        // Publishing twice is refused.
        ApiException twice = assertThrows(ApiException.class, () -> shortlistService.publish(TNP, p.getId(), picks));
        assertEquals("SHORTLIST_PUBLISHED", twice.getCode());
    }

    // ---------------------------------------------------------------- reopen

    private org.yuktisetu.driveservice.dto.ReopenDriveRequest reopenUntil(Instant deadline) {
        return new org.yuktisetu.driveservice.dto.ReopenDriveRequest(deadline, "Company asked for more applicants");
    }

    @Test
    @DisplayName("reopening a published drive discards its ranking and shortlist, and the next seal ranks the bigger pool")
    void reopenAfterPublishReranksTheBiggerPool() {
        JobPosting p = aPosting(BigDecimal.valueOf(50), BigDecimal.valueOf(50), BigDecimal.ZERO, 2);
        List<Applicant> first = pool(p, 10);
        sealAndScore(p);
        DriveScoreRun firstRun = runOf(p);
        StaffDriveScores screen = shortlistService.scores(TNP, p.getId());
        List<Long> picks = screen.items().subList(0, 2).stream().map(i -> i.applicationId()).toList();
        shortlistService.publish(TNP, p.getId(), picks);
        User wasShortlisted = applicationRepository.findById(picks.get(0)).orElseThrow().getStudent();

        Instant newDeadline = Instant.now().plusSeconds(3600);
        var result = reopenService.reopen(TNP, p.getId(), reopenUntil(newDeadline));
        assertEquals(10, result.applicationsReopened());
        assertEquals(2, result.previouslyShortlisted());
        assertEquals(firstRun.getId(), result.supersededRunId());
        assertEquals(1, result.reopenCount());

        JobPosting reopened = postingRepository.findById(p.getId()).orElseThrow();
        assertEquals(PostingStatus.PUBLISHED, reopened.getStatus());
        assertNull(reopened.getShortlistPublishedAt(), "the shortlist is no longer published");
        assertNull(reopened.getApplicationsClosedAt());
        assertNotNull(reopened.getReopenedAt());
        assertTrue(gateRepository.findByPostingId(p.getId()).isEmpty(), "nothing can rank a drive that is open again");
        assertTrue(runRepository.findByPostingIdAndSupersededAtIsNull(p.getId()).isEmpty(), "no active run");
        assertNotNull(runRepository.findById(firstRun.getId()).orElseThrow().getSupersededAt(),
                "the old run is kept as history, marked superseded");
        for (Applicant a : first) {
            var app = applicationRepository.findById(applicationIdOf(p, a.user())).orElseThrow();
            assertEquals(ApplicationStatus.APPLIED, app.getStatus());
            assertNull(app.getSealedAt());
        }
        assertEquals(List.of(p.getId()), applicationRepository.findUnderReReview(wasShortlisted.getId(), List.of(p.getId())),
                "a student shortlisted before the reopen is under re-review");
        assertTrue(applicationRepository.findUnderReReview(first.get(0).user().getId(), List.of(p.getId())).isEmpty()
                || picks.contains(applicationIdOf(p, first.get(0).user())), "a rejected student is not");
        Integer reopenNotices = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_type = 'JobPosting' AND aggregate_id = ? "
                        + "AND payload::text LIKE ?", Integer.class, String.valueOf(p.getId()),
                "%" + NotificationTemplates.DRIVE_REOPENED + "%");
        assertEquals(2, reopenNotices, "one batch for the previously shortlisted, one for the other applicants");

        // Two more students apply during the reopened window; nobody is scraped again.
        int jobsBefore = jdbc.queryForObject("SELECT count(*) FROM coding_scrape_jobs", Integer.class);
        pool(p, 2);
        assertEquals(jobsBefore + 2, jdbc.queryForObject("SELECT count(*) FROM coding_scrape_jobs", Integer.class),
                "only the new applicants are scraped; captures of the first ten are reused");

        sealAndScore(p);
        DriveScoreRun second = runOf(p);
        assertNotEquals(firstRun.getId(), second.getId());
        assertEquals(ScoreRunStatus.SCORED, second.getStatus());
        assertEquals(12, second.getPoolSize(), "the new ranking covers the whole new pool");
        assertEquals(12, scoreRepository.findByRunIdOrderByRankAscStudentUserIdAsc(second.getId()).size());
        assertEquals(10, scoreRepository.findByRunIdOrderByRankAscStudentUserIdAsc(firstRun.getId()).size(),
                "the superseded run keeps its rows");

        StaffDriveScores again = shortlistService.scores(TNP, p.getId());
        assertTrue(again.canPublish(), "the drive can be shortlisted again");
        assertEquals(12, again.items().size());
        shortlistService.publish(TNP, p.getId(), again.items().subList(0, 2).stream().map(i -> i.applicationId()).toList());
        assertTrue(applicationRepository.findUnderReReview(wasShortlisted.getId(), List.of(p.getId())).isEmpty(),
                "re-review ends when the new shortlist is published");

        setVisible(true);
        StudentScoresResponse mine = studentScoreService.myScores(wasShortlisted.getId());
        assertEquals(1, mine.items().size(), "a student sees only the current ranking, never the superseded one");
        assertEquals(12, mine.items().get(0).poolSize());
    }

    @Test
    @DisplayName("a reopen is refused while applications are open, and for a deadline that is not in the future")
    void reopenGuards() {
        JobPosting p = aPosting(BigDecimal.valueOf(1), BigDecimal.ZERO, BigDecimal.ZERO, 2);
        ApiException open = assertThrows(ApiException.class,
                () -> reopenService.reopen(TNP, p.getId(), reopenUntil(Instant.now().plusSeconds(3600))));
        assertEquals("STILL_OPEN", open.getCode());

        pool(p, 10);
        sealAndScore(p);
        ApiException past = assertThrows(ApiException.class,
                () -> reopenService.reopen(TNP, p.getId(), reopenUntil(Instant.now().plusSeconds(60))));
        assertEquals("INVALID_REQUEST", past.getCode());
        assertEquals(PostingStatus.APPLICATIONS_CLOSED, postingRepository.findById(p.getId()).orElseThrow().getStatus(),
                "a refused reopen changes nothing");
        assertNotNull(runRepository.findByPostingIdAndSupersededAtIsNull(p.getId()).orElse(null));
    }

    // ------------------------------------------------- unranked platforms

    @Test
    @DisplayName("CodeChef is never ranked: a strong CodeChef record does not lift a student, even when captured")
    void codechefIsNotRanked() {
        JobPosting p = aPosting(BigDecimal.ZERO.add(BigDecimal.ONE), BigDecimal.ONE, BigDecimal.ZERO, null);
        pool(p, 10);
        // LeetCode below every pool member, CodeChef at the very top.
        User s = aStudent(6.0, Map.of(CodingPlatform.LEETCODE, "score_lc_weak", CodingPlatform.CODECHEF, "score_cc_strong"));
        apply(p, s);
        scrape(s, "LEETCODE", leetcode(1000));
        scrape(s, "CODECHEF", "{\"rating\": 3000, \"contests_attended\": 100, \"solved_total\": 900}");
        sealAndScore(p);

        DriveScoreRun run = runOf(p);
        assertEquals(ScoreRunStatus.SCORED, run.getStatus(), "no hold: the student has a ranked platform");
        ApplicationScore mine = scoreRepository.findByRunIdOrderByRankAscStudentUserIdAsc(run.getId()).stream()
                .filter(x -> x.getStudentUserId().equals(s.getId())).findFirst().orElseThrow();
        assertEquals(11, mine.getRank(), "judged on LeetCode alone, where they are last");
        String breakdown = mine.getBreakdown().replace(" ", "");
        assertTrue(breakdown.contains("\"platform\":\"LeetCode\""), breakdown);
        // Were CodeChef ranked, its cohort of one would be listed as dropped.
        assertTrue(run.getCodingDropped() == null || !run.getCodingDropped().contains("CodeChef"),
                "CodeChef is never even considered: " + run.getCodingDropped());
    }

    @Test
    @DisplayName("a student whose only listed profile is not ranked is held as 'not measured', never put at the bottom")
    void unrankedOnlyIsNotMeasuredNotNone() {
        JobPosting p = aPosting(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, null);
        pool(p, 10);
        User ccOnly = aStudent(9.5, Map.of(CodingPlatform.CODECHEF, "score_cc_only"));
        apply(p, ccOnly);
        scrape(ccOnly, "CODECHEF", "{\"rating\": 2500, \"contests_attended\": 50, \"solved_total\": 500}");
        User none = aStudent(9.5, Map.of());
        apply(p, none);
        sealAndScore(p);

        DriveScoreRun run = runOf(p);
        assertEquals(ScoreRunStatus.HELD_FOR_REVIEW, run.getStatus());
        assertEquals(1, run.getHeldCount(), "only the CodeChef-only student is held");
        assertTrue(run.getHeldStudents().contains(String.valueOf(ccOnly.getId())));
        assertTrue(run.getHeldStudents().contains("not ranked"));
        assertFalse(run.getHeldStudents().contains(String.valueOf(none.getId())),
                "a student who listed nothing is measured as none, as before");

        shortlistService.approveHeld(TNP, p.getId());
        DriveScoreRun approved = runOf(p);
        List<ApplicationScore> rows = scoreRepository.findByRunIdOrderByRankAscStudentUserIdAsc(approved.getId());
        ApplicationScore cc = rows.stream().filter(x -> x.getStudentUserId().equals(ccOnly.getId())).findFirst().orElseThrow();
        ApplicationScore nothing = rows.stream().filter(x -> x.getStudentUserId().equals(none.getId())).findFirst().orElseThrow();
        assertTrue(cc.isWeightsRenormalised(), "coding left out, CGPA counts for 100%");
        assertTrue(cc.getComposite().compareTo(nothing.getComposite()) > 0,
                "same CGPA: the CodeChef-only student is not ranked down to the no-profile student");
    }
}
