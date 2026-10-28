package org.yuktisetu.driveservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.drive.db.ApplicationCodingReview;
import org.yuktisetu.drive.db.ApplicationScore;
import org.yuktisetu.drive.db.DriveScoreRun;
import org.yuktisetu.drive.db.DriveScoringGate;
import org.yuktisetu.drive.db.JobPosting;
import org.yuktisetu.drive.db.PostingTarget;
import org.yuktisetu.drive.model.ActorKind;
import org.yuktisetu.drive.model.ApplicationStatus;
import org.yuktisetu.drive.model.CodingReviewDecision;
import org.yuktisetu.drive.model.ScoreRunStatus;
import org.yuktisetu.drive.model.ShortlistingMode;
import org.yuktisetu.drive.repository.ApplicationCodingReviewRepository;
import org.yuktisetu.drive.repository.ApplicationScoreRepository;
import org.yuktisetu.drive.repository.DriveScoreRunRepository;
import org.yuktisetu.drive.repository.DriveScoringGateRepository;
import org.yuktisetu.drive.repository.JobApplicationRepository;
import org.yuktisetu.drive.repository.JobPostingRepository;
import org.yuktisetu.drive.repository.PlatformSettingsRepository;
import org.yuktisetu.drive.repository.PostingTargetRepository;
import org.yuktisetu.driveservice.dto.ScoreResponses.HeldStudent;
import org.yuktisetu.driveservice.dto.ScoreResponses.StaffDriveScores;
import org.yuktisetu.driveservice.dto.ScoreResponses.StaffScoreItem;
import org.yuktisetu.driveservice.exception.DriveExceptions;
import org.yuktisetu.driveservice.policy.CodingReviewPolicy;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The TnP's side of composite scores: read the ranked pool, approve a held run,
 * and publish the shortlist.
 *
 * Lives in drive-service beside the coding review for the same reason: it acts on
 * the gate, the scores and the application statuses together.
 *
 * Publishing is decided for the whole drive at once, so it needs authority over
 * EVERY college the drive targets -- unlike a coding-review decision, which is
 * per student. Reading is open to anyone who can open the coding review.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShortlistService {

    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {};

    private final JobPostingRepository postingRepository;
    private final PostingTargetRepository targetRepository;
    private final DriveScoringGateRepository gateRepository;
    private final DriveScoreRunRepository runRepository;
    private final ApplicationScoreRepository scoreRepository;
    private final ApplicationCodingReviewRepository reviewRepository;
    private final JobApplicationRepository applicationRepository;
    private final PlatformSettingsRepository settingsRepository;
    private final ScoringRunService scoringRunService;
    private final DriveNotificationService notificationService;
    private final CodingReviewPolicy policy;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------------- read

    @Transactional(readOnly = true)
    public StaffDriveScores scores(UserPrincipal actor, Long postingId) {
        JobPosting posting = authorised(actor, postingId, false);
        return read(actor, posting);
    }

    private StaffDriveScores read(UserPrincipal actor, JobPosting posting) {
        Long postingId = posting.getId();
        DriveScoringGate gate = gateRepository.findByPostingId(postingId).orElse(null);
        DriveScoreRun run = runRepository.findByPostingIdAndSupersededAtIsNull(postingId).orElse(null);

        Map<Long, ApplicationCodingReview> reviews = new HashMap<>();
        reviewRepository.findByPostingId(postingId).forEach(r -> reviews.put(r.getApplicationId(), r));
        int direct = (int) reviews.values().stream()
                .filter(r -> r.getDecision() == CodingReviewDecision.SHORTLIST_DIRECTLY).count();
        Integer target = posting.getMaxApplications();

        // The pool as it stands: sealed applications, plus any already decided by a
        // published shortlist, so the screen still reads after publishing.
        Map<Long, Object[]> pool = new LinkedHashMap<>();
        jdbc.query("""
                SELECT a.id, a.student_user_id, a.status
                  FROM job_applications a
                 WHERE a.posting_id = :postingId
                   AND a.status IN ('IN_REVIEW','SHORTLISTED','REJECTED')
                   AND a.is_deleted = false
                 ORDER BY a.id
                """, new MapSqlParameterSource("postingId", postingId),
                rs -> { pool.put(rs.getLong(1), new Object[]{rs.getLong(2), rs.getString(3)}); });

        Map<Long, String[]> people = people(pool.values().stream().map(r -> (Long) r[0]).toList());
        Map<Long, ApplicationScore> scores = new HashMap<>();
        if (run != null && run.getStatus() == ScoreRunStatus.SCORED) {
            scoreRepository.findByRunIdOrderByRankAscStudentUserIdAsc(run.getId())
                    .forEach(s -> scores.put(s.getApplicationId(), s));
        }

        List<StaffScoreItem> items = new ArrayList<>(pool.size());
        pool.forEach((appId, r) -> {
            Long student = (Long) r[0];
            String[] who = people.getOrDefault(student, new String[]{"", ""});
            ApplicationScore s = scores.get(appId);
            ApplicationCodingReview review = reviews.get(appId);
            items.add(new StaffScoreItem(
                    appId, student, who[0], who[1], (String) r[1],
                    review == null ? null : review.getDecision().name(),
                    s == null ? null : s.getRank(),
                    s != null && s.isTied(),
                    s == null ? null : s.getComposite(),
                    s == null ? null : s.isMeetsThreshold(),
                    s == null ? null : s.isShortlistEligible(),
                    s == null || s.getFloorsFailed() == null ? List.of()
                            : Arrays.asList(s.getFloorsFailed().split(",")),
                    s == null ? null : json(s.getBreakdown())));
        });
        // Ranked first, in rank order; then everyone without a score.
        items.sort((a, b) -> {
            if (a.rank() == null && b.rank() == null) return Long.compare(a.applicationId(), b.applicationId());
            if (a.rank() == null) return 1;
            if (b.rank() == null) return -1;
            int c = Integer.compare(a.rank(), b.rank());
            return c != 0 ? c : Long.compare(a.studentUserId(), b.studentUserId());
        });

        List<HeldStudent> held = List.of();
        if (run != null && run.getStatus() == ScoreRunStatus.HELD_FOR_REVIEW && run.getHeldStudents() != null) {
            Map<String, Object> raw = json(run.getHeldStudents());
            Map<Long, String[]> heldPeople = people(raw.keySet().stream().map(Long::valueOf).toList());
            held = new ArrayList<>();
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                Long id = Long.valueOf(e.getKey());
                String[] who = heldPeople.getOrDefault(id, new String[]{"", ""});
                @SuppressWarnings("unchecked")
                Map<String, Object> reasons = (Map<String, Object>) e.getValue();
                held.add(new HeldStudent(id, who[0], who[1], reasons));
            }
        }

        String runStatus = run == null ? "NOT_SCORED" : run.getStatus().name();
        boolean canPublish = posting.getShortlistPublishedAt() == null
                && run != null
                && (run.getStatus() == ScoreRunStatus.SCORED || run.getStatus() == ScoreRunStatus.DECLINED)
                && coversEveryTarget(actor, postingId);

        return new StaffDriveScores(
                postingId, posting.getCompany().getName(), posting.getTitle(),
                gate == null ? "NOT_SEALED" : gate.getState().name(),
                runStatus,
                run == null ? null : run.getScoredAt(),
                run == null ? null : run.getDeclinedReason(),
                run == null ? null : json(run.getConfig()),
                run == null ? null : json(run.getPoolExclusions()),
                run == null ? null : json(run.getCodingDropped()),
                held,
                target, direct,
                target == null ? null : Math.max(0, target - direct),
                posting.getShortlistPublishedAt(),
                canPublish,
                items);
    }

    // ---------------------------------------------------------------- approve

    @Transactional
    public StaffDriveScores approveHeld(UserPrincipal actor, Long postingId) {
        JobPosting posting = authorised(actor, postingId, true);
        scoringRunService.approveHeld(postingId, actor.userId());
        return read(actor, posting);
    }

    // ---------------------------------------------------------------- publish

    /**
     * Publish the shortlist, once.
     *
     * The selected applications and the coding review's direct picks become
     * SHORTLISTED; everyone else still in the sealed pool becomes REJECTED. Both
     * changes are audited in application_status_history, the posting is stamped,
     * and each student is told in one batched notice per outcome -- all in this
     * transaction, under the posting's write lock, so a double click cannot
     * publish twice.
     */
    @Transactional
    public StaffDriveScores publish(UserPrincipal actor, Long postingId, Collection<Long> selected) {
        // Lock first, so the published-at check below reads the committed value
        // rather than an entity loaded before the lock was held.
        JobPosting posting = postingRepository.findForSealing(postingId)
                .orElseThrow(() -> new DriveExceptions.DriveNotVisibleException(postingId));
        authorised(actor, postingId, true);
        if (posting.getShortlistPublishedAt() != null) {
            throw new DriveExceptions.ScoringStateException("SHORTLIST_PUBLISHED",
                    "The shortlist for this drive was already published.");
        }
        if (posting.getShortlistingMode() != ShortlistingMode.COMPOSITE_SCORE) {
            throw new DriveExceptions.ScoringStateException("NOT_COMPOSITE_SCORE",
                    "This drive does not use composite-score shortlisting.");
        }
        DriveScoreRun run = runRepository.findByPostingIdAndSupersededAtIsNull(postingId).orElse(null);
        if (run == null || run.getStatus() == ScoreRunStatus.HELD_FOR_REVIEW) {
            throw new DriveExceptions.ScoringStateException("NOT_SCORED", run == null
                    ? "This drive has not been scored yet."
                    : "Scoring is waiting for your approval of the held students.");
        }

        Map<Long, Long> pool = new LinkedHashMap<>();
        for (Object[] r : applicationRepository.findSealedPool(postingId)) {
            pool.put(((Number) r[0]).longValue(), ((Number) r[1]).longValue());
        }
        Map<Long, CodingReviewDecision> decided = new HashMap<>();
        reviewRepository.findByPostingId(postingId).forEach(r -> decided.put(r.getApplicationId(), r.getDecision()));

        Set<Long> shortlisted = new HashSet<>();
        for (Long id : selected) {
            if (!pool.containsKey(id)) {
                throw new DriveExceptions.InvalidRequestException(
                        "Application " + id + " is not in this drive's sealed pool.");
            }
            if (decided.get(id) == CodingReviewDecision.REMOVE) {
                throw new DriveExceptions.InvalidRequestException(
                        "Application " + id + " was removed in the coding review and cannot be shortlisted.");
            }
            shortlisted.add(id);
        }
        decided.forEach((id, d) -> {
            if (d == CodingReviewDecision.SHORTLIST_DIRECTLY && pool.containsKey(id)) {
                shortlisted.add(id);
            }
        });
        Set<Long> rejected = new HashSet<>(pool.keySet());
        rejected.removeAll(shortlisted);

        Instant now = Instant.now();
        String reason = "Shortlist published";
        if (!shortlisted.isEmpty()) {
            applicationRepository.recordHistoryForIds(shortlisted, ApplicationStatus.IN_REVIEW.name(),
                    ApplicationStatus.SHORTLISTED.name(), now, actor.userId(), ActorKind.TNP.name(), reason);
            applicationRepository.updateStatusForIds(shortlisted, ApplicationStatus.IN_REVIEW,
                    ApplicationStatus.SHORTLISTED, actor.userId());
        }
        if (!rejected.isEmpty()) {
            applicationRepository.recordHistoryForIds(rejected, ApplicationStatus.IN_REVIEW.name(),
                    ApplicationStatus.REJECTED.name(), now, actor.userId(), ActorKind.TNP.name(), reason);
            applicationRepository.updateStatusForIds(rejected, ApplicationStatus.IN_REVIEW,
                    ApplicationStatus.REJECTED, actor.userId());
        }

        // The bulk updates cleared the persistence context; re-read before stamping.
        posting = postingRepository.findById(postingId).orElseThrow();
        posting.setShortlistPublishedAt(now);
        posting.setShortlistPublishedBy(actor.userId());
        postingRepository.save(posting);

        boolean scoresVisible = settingsRepository.current().isShowScoresToStudents()
                && run.getStatus() == ScoreRunStatus.SCORED;
        notificationService.queueShortlistResult(postingId,
                shortlisted.stream().map(pool::get).toList(),
                rejected.stream().map(pool::get).toList(),
                scoresVisible);

        log.info("Shortlist published: postingId={} actorId={} shortlisted={} rejected={} run={}",
                postingId, actor.userId(), shortlisted.size(), rejected.size(), run.getStatus());
        return read(actor, posting);
    }

    /**
     * The same authority as publishing a shortlist -- coordinator and above, over
     * every target college -- for actions that change the whole drive.
     */
    public void authoriseWholeDrive(UserPrincipal actor, Long postingId) {
        authorised(actor, postingId, true);
    }

    // ---------------------------------------------------------------- helpers

    private JobPosting authorised(UserPrincipal actor, Long postingId, boolean forWholeDrive) {
        JobPosting posting = postingRepository.findById(postingId)
                .filter(p -> !p.isDeleted())
                .orElseThrow(() -> new DriveExceptions.DriveNotVisibleException(postingId));
        List<Long> colleges = targetColleges(postingId);
        if (!policy.canOpenReview(actor, colleges)) {
            throw new DriveExceptions.ScopeViolationException();
        }
        if (forWholeDrive && !coversEveryTarget(actor, postingId)) {
            log.warn("Shortlist action refused: actorId={} postingId={} does not cover every target college",
                    actor.userId(), postingId);
            throw new DriveExceptions.ScopeViolationException();
        }
        return posting;
    }

    private boolean coversEveryTarget(UserPrincipal actor, Long postingId) {
        return targetColleges(postingId).stream().allMatch(c -> policy.canReviewCollege(actor, c));
    }

    private List<Long> targetColleges(Long postingId) {
        return targetRepository.findByPosting_IdAndIsDeletedFalse(postingId).stream()
                .map(PostingTarget::getCollegeId).distinct().toList();
    }

    /** userId -> [name, email], one query. */
    private Map<Long, String[]> people(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String[]> out = new HashMap<>();
        jdbc.query("SELECT id, first_name, last_name, email FROM users WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", userIds),
                rs -> { out.put(rs.getLong(1), new String[]{
                        ((rs.getString(2) == null ? "" : rs.getString(2)) + " "
                                + (rs.getString(3) == null ? "" : rs.getString(3))).trim(),
                        rs.getString(4)}); });
        return out;
    }

    private Map<String, Object> json(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return objectMapper.readValue(raw, JSON_MAP);
    }
}
