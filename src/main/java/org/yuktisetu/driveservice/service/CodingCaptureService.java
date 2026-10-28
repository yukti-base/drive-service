package org.yuktisetu.driveservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.drive.db.ApplicationCodingCapture;
import org.yuktisetu.drive.db.JobApplication;
import org.yuktisetu.drive.db.JobPosting;
import org.yuktisetu.drive.model.CaptureState;
import org.yuktisetu.drive.model.ShortlistingMode;
import org.yuktisetu.drive.repository.ApplicationCodingCaptureRepository;
import org.yuktisetu.profile.model.CodingPlatform;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/**
 * Captures a student's coding data at the moment they apply.
 *
 * Runs INSIDE the apply transaction, by decision: an application and its
 * capture are one fact. If the capture rows cannot be written, the apply rolls
 * back and the student is told to try again -- the alternative, an application
 * that silently has no coding data, is a student scored on nothing with no one
 * aware of it.
 *
 * What it does NOT do is scrape. It writes a coding_scrape_jobs row, and a
 * database trigger wakes the scraper when this transaction commits. The student
 * never waits on a platform, and a scraper that is down costs nothing at apply
 * time: the job waits in the table.
 *
 * Every database call here is per STUDENT, never per platform -- one read of the
 * profiles, one multi-row insert of jobs, at most one locked read of jobs that
 * already existed, one batch save of captures.
 */
@Slf4j
@Service
public class CodingCaptureService {

    /** Reason recorded on the job; must match coding_scrape_jobs_reason_check. */
    static final String REASON_APPLY = "APPLY";

    /** Reason recorded on a job the student asked for by re-checking a handle. */
    static final String REASON_RECHECK = "RECHECK";

    /** Outcome written when a student lists a platform but no handle. */
    static final String OUTCOME_NO_HANDLE = "NO_HANDLE";

    private final JdbcTemplate jdbc;
    private final ApplicationCodingCaptureRepository captureRepository;
    private final DriveNotificationService notificationService;
    private final Set<CodingPlatform> capturedPlatforms;
    private final int giveUpMinutes;

    public CodingCaptureService(
            JdbcTemplate jdbc,
            ApplicationCodingCaptureRepository captureRepository,
            DriveNotificationService notificationService,
            @Value("${yuktisetu.coding.capture.platforms:LEETCODE,CODEFORCES}") String platforms,
            @Value("${yuktisetu.coding.capture.give-up-minutes:70}") int giveUpMinutes) {
        this.jdbc = jdbc;
        this.captureRepository = captureRepository;
        this.notificationService = notificationService;
        this.capturedPlatforms = Arrays.stream(platforms.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(CodingPlatform::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(CodingPlatform.class)));
        this.giveUpMinutes = giveUpMinutes;
        log.info("Coding capture at apply: platforms={} giveUpMinutes={}", capturedPlatforms, giveUpMinutes);
    }

    /**
     * Queue this application's captures. Must run inside the apply transaction.
     *
     * Only COMPOSITE_SCORE drives are captured. The other modes never compute a
     * score, so scraping for them would spend the platforms' patience on data
     * nothing reads.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void captureForApplication(JobApplication application, JobPosting posting, Instant appliedAt) {
        if (posting.getShortlistingMode() != ShortlistingMode.COMPOSITE_SCORE) {
            log.debug("No coding capture: postingId={} mode={}", posting.getId(), posting.getShortlistingMode());
            return;
        }
        Long studentUserId = application.getStudentUserId();

        // A re-apply after a withdrawal is a new application moment, so whatever
        // was captured for the previous one is retired rather than reused.
        int superseded = captureRepository.supersedeForApplication(application.getId());
        if (superseded > 0) {
            log.info("Superseded {} earlier capture(s) for applicationId={} on re-apply",
                    superseded, application.getId());
        }

        Map<CodingPlatform, String> handles = handlesFor(studentUserId);
        if (handles.isEmpty()) {
            // Not an error and not a review case: a student with no coding
            // accounts has measured-none on the coding factor, which the scorer
            // ranks at the bottom of that factor.
            log.info("No coding profiles to capture: applicationId={} studentUserId={}",
                    application.getId(), studentUserId);
            return;
        }

        List<ApplicationCodingCapture> captures = new ArrayList<>(handles.size());
        Map<CodingPlatform, String> toScrape = new LinkedHashMap<>();

        for (Map.Entry<CodingPlatform, String> e : handles.entrySet()) {
            ApplicationCodingCapture c = newCapture(application, posting, studentUserId, e.getKey(), appliedAt);
            c.setHandle(e.getValue());
            if (e.getValue() == null || e.getValue().isBlank()) {
                // The student listed the platform but no username. Nothing to
                // scrape, and it is theirs to fix: they are asked to add it and
                // re-check, exactly as for a handle that was not found. The
                // deadline is open by definition -- they are applying.
                CaptureOutcomePolicy.settle(c, posting, false, null, OUTCOME_NO_HANDLE,
                        "The student listed " + e.getKey().label() + " without a username.", appliedAt);
            } else {
                toScrape.put(e.getKey(), e.getValue());
            }
            captures.add(c);
        }

        Map<CodingPlatform, Long> jobIds = toScrape.isEmpty()
                ? Map.of()
                : enqueueOrJoin(studentUserId, posting.getId(), toScrape, REASON_APPLY);

        for (ApplicationCodingCapture c : captures) {
            if (c.getState() == CaptureState.PENDING) {
                c.setJobId(jobIds.get(c.getPlatform()));
            }
        }
        captureRepository.saveAll(captures);

        for (ApplicationCodingCapture c : captures) {
            if (c.getState() == org.yuktisetu.drive.model.CaptureState.AWAITING_STUDENT) {
                notificationService.queueHandleCheckNeeded(studentUserId, c.getPlatform(), c.getHandle(),
                        c.getOutcome(), List.of(posting.getId()));
            }
        }

        log.info("Coding capture queued: applicationId={} postingId={} studentUserId={} platforms={} jobs={}",
                application.getId(), posting.getId(), studentUserId, handles.keySet(), jobIds);
    }

    /** Whether the scraper reads this platform at all. */
    boolean captures(CodingPlatform platform) {
        return capturedPlatforms.contains(platform);
    }

    private ApplicationCodingCapture newCapture(JobApplication application, JobPosting posting,
                                                Long studentUserId, CodingPlatform platform, Instant at) {
        ApplicationCodingCapture c = new ApplicationCodingCapture();
        c.setApplicationId(application.getId());
        c.setPostingId(posting.getId());
        c.setStudentUserId(studentUserId);
        c.setPlatform(platform);
        c.setState(CaptureState.PENDING);
        c.setRequestedAt(at);
        return c;
    }

    /**
     * The student's handles on the platforms the scraper can read, one per platform.
     *
     * One query. A student listing the same platform twice keeps the first entry:
     * a score is per platform, and two accounts on one platform cannot both be
     * "the" account. Platforms with no adapter (GeeksforGeeks and the rest) are
     * skipped here rather than queued, because a job no adapter can run would
     * fail and flag every such student for review over our own gap.
     */
    Map<CodingPlatform, String> handlesFor(Long studentUserId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT cp.platform, cp.username
                  FROM student_coding_profiles cp
                  JOIN student_profiles sp ON sp.id = cp.student_profile_id
                 WHERE sp.user_id = ?
                 ORDER BY cp.id
                """, studentUserId);

        Map<CodingPlatform, String> handles = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            CodingPlatform platform = CodingPlatform.fromLabel((String) r.get("platform"));
            if (!capturedPlatforms.contains(platform)) {
                log.debug("Not captured (no adapter): studentUserId={} platform={}", studentUserId, platform);
                continue;
            }
            if (handles.containsKey(platform)) {
                log.warn("studentUserId={} lists {} more than once; capturing the first entry only",
                        studentUserId, platform);
                continue;
            }
            String username = (String) r.get("username");
            handles.put(platform, username == null ? null : username.trim());
        }
        return handles;
    }

    /**
     * Insert a job per platform, or join the one already pending.
     *
     * The partial unique index allows one PENDING/RUNNING job per (student,
     * platform), so a student applying to two drives a second apart shares one
     * scrape and both captures are filled by it.
     *
     * Joining takes the existing row FOR UPDATE. That is load-bearing: the
     * scraper's final UPDATE on the job then waits for this transaction to commit,
     * so its completion event is always written AFTER this capture row exists.
     * Without the lock, the job could finish in the gap and this capture would
     * wait for an event that had already gone past.
     *
     * Two rounds at most. The only way a platform is missing after round one is a
     * job that finished between our insert and our locked read, and round two then
     * inserts a fresh one -- which the unique index guarantees cannot conflict
     * again, because we now hold the competing row.
     */
    Map<CodingPlatform, Long> enqueueOrJoin(Long studentUserId, Long postingId,
                                            Map<CodingPlatform, String> handles, String reason) {
        Map<CodingPlatform, Long> jobIds = new LinkedHashMap<>();
        Map<CodingPlatform, String> remaining = new LinkedHashMap<>(handles);

        for (int round = 1; round <= 2 && !remaining.isEmpty(); round++) {
            jobIds.putAll(insertJobs(studentUserId, postingId, remaining, reason));
            remaining.keySet().removeAll(jobIds.keySet());
            if (remaining.isEmpty()) {
                break;
            }
            Map<CodingPlatform, Long> joined = lockExistingJobs(studentUserId, remaining);
            jobIds.putAll(joined);
            remaining.keySet().removeAll(joined.keySet());
        }
        if (!remaining.isEmpty()) {
            // Cannot happen under the reasoning above. If it ever does, refusing
            // the apply is better than an application with a capture nothing fills.
            throw new IllegalStateException("could not queue coding capture for studentUserId="
                    + studentUserId + " platforms=" + remaining.keySet());
        }
        return jobIds;
    }

    private Map<CodingPlatform, Long> insertJobs(Long studentUserId, Long postingId,
                                                 Map<CodingPlatform, String> handles, String reason) {
        StringJoiner values = new StringJoiner(", ");
        List<Object> args = new ArrayList<>();
        Timestamp giveUpAt = Timestamp.from(Instant.now().plusSeconds(giveUpMinutes * 60L));
        for (Map.Entry<CodingPlatform, String> e : handles.entrySet()) {
            // Every column explicit, including those with defaults in the real
            // schema, so this also works against a create-drop test schema.
            values.add("(?, ?, ?, ?, ?, 'PENDING', 0, now(), now(), now(), ?)");
            args.add(studentUserId);
            args.add(e.getKey().name());
            args.add(e.getValue());
            args.add(reason);
            args.add(postingId);
            args.add(giveUpAt);
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "INSERT INTO coding_scrape_jobs (student_user_id, platform, handle, reason, posting_id, "
                        + "status, attempts, available_at, created_at, updated_at, give_up_at) VALUES "
                        + values + " ON CONFLICT DO NOTHING RETURNING id, platform",
                args.toArray());
        return toJobIds(rows);
    }

    private Map<CodingPlatform, Long> lockExistingJobs(Long studentUserId, Map<CodingPlatform, String> handles) {
        String placeholders = String.join(", ", Collections.nCopies(handles.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(studentUserId);
        handles.keySet().forEach(p -> args.add(p.name()));

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, platform, handle, status FROM coding_scrape_jobs "
                        + "WHERE student_user_id = ? AND platform IN (" + placeholders + ") "
                        + "AND status IN ('PENDING','RUNNING') FOR UPDATE",
                args.toArray());

        // A job that is still PENDING but was queued under an older handle is
        // corrected, so it reads the account the student has now. A RUNNING one is
        // mid-request and is left alone; the mismatch is logged for the TnP sheet.
        List<Object[]> corrections = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            CodingPlatform platform = CodingPlatform.valueOf((String) r.get("platform"));
            String wanted = handles.get(platform);
            if (wanted != null && !wanted.equals(r.get("handle"))) {
                if ("PENDING".equals(r.get("status"))) {
                    corrections.add(new Object[]{wanted, ((Number) r.get("id")).longValue()});
                } else {
                    log.warn("studentUserId={} {} job {} is already running with handle '{}', not '{}'",
                            studentUserId, platform, r.get("id"), r.get("handle"), wanted);
                }
            }
        }
        if (!corrections.isEmpty()) {
            jdbc.batchUpdate("UPDATE coding_scrape_jobs SET handle = ?, updated_at = now() WHERE id = ?",
                    corrections);
        }
        log.debug("Joined existing scrape job(s) for studentUserId={}: {}", studentUserId, rows);
        return toJobIds(rows);
    }

    private static Map<CodingPlatform, Long> toJobIds(List<Map<String, Object>> rows) {
        Map<CodingPlatform, Long> out = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            out.put(CodingPlatform.valueOf((String) r.get("platform")), ((Number) r.get("id")).longValue());
        }
        return out;
    }
}
