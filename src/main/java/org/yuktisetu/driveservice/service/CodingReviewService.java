package org.yuktisetu.driveservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.drive.db.ApplicationCodingCapture;
import org.yuktisetu.drive.db.ApplicationCodingReview;
import org.yuktisetu.drive.db.DriveScoringGate;
import org.yuktisetu.drive.db.JobPosting;
import org.yuktisetu.drive.db.PostingTarget;
import org.yuktisetu.drive.model.CodingReviewDecision;
import org.yuktisetu.drive.model.ScoringGateState;
import org.yuktisetu.drive.repository.ApplicationCodingCaptureRepository;
import org.yuktisetu.drive.repository.ApplicationCodingReviewRepository;
import org.yuktisetu.drive.repository.DriveScoringGateRepository;
import org.yuktisetu.drive.repository.JobPostingRepository;
import org.yuktisetu.drive.repository.PostingTargetRepository;
import org.yuktisetu.driveservice.dto.CodingReviewDecisionRequest;
import org.yuktisetu.driveservice.dto.CodingReviewResponse;
import org.yuktisetu.driveservice.dto.CodingReviewResponse.CaptureView;
import org.yuktisetu.driveservice.dto.CodingReviewResponse.CertificationView;
import org.yuktisetu.driveservice.dto.CodingReviewResponse.CodingProfileView;
import org.yuktisetu.driveservice.dto.CodingReviewResponse.DecisionView;
import org.yuktisetu.driveservice.dto.CodingReviewResponse.ExperienceView;
import org.yuktisetu.driveservice.dto.CodingReviewResponse.Item;
import org.yuktisetu.driveservice.dto.CodingReviewResponse.ProjectView;
import org.yuktisetu.driveservice.dto.CodingReviewResponse.SnapshotView;
import org.yuktisetu.driveservice.exception.DriveExceptions;
import org.yuktisetu.driveservice.policy.CodingReviewPolicy;
import org.yuktisetu.profile.model.CodingPlatform;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The TnP's coding-review screen: read it, and record decisions from it.
 *
 * Hosted in drive-service, not admin-service, because the decision and the drive
 * becoming ready must be one transaction under the scoring gate's lock, and the
 * gate lives here. The usual reason staff endpoints live in admin-service -- so
 * staff never queue behind students mid-drive -- does not bite: a review only
 * exists after the deadline, when student traffic on this service has ended.
 *
 * Reading is a fixed number of queries however many students are flagged --
 * one per kind of detail, each for the whole page -- never one per student.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodingReviewService {

    private final JobPostingRepository postingRepository;
    private final PostingTargetRepository targetRepository;
    private final DriveScoringGateRepository gateRepository;
    private final ApplicationCodingCaptureRepository captureRepository;
    private final ApplicationCodingReviewRepository reviewRepository;
    private final ScoringGateService scoringGateService;
    private final CodingReviewPolicy policy;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    // ---------------------------------------------------------------- read

    @Transactional(readOnly = true)
    public CodingReviewResponse review(UserPrincipal actor, Long postingId) {
        JobPosting posting = authorisedPosting(actor, postingId);
        DriveScoringGate gate = gateRepository.findByPostingId(postingId).orElse(null);

        Map<Long, Long> flagged = new LinkedHashMap<>();      // applicationId -> studentUserId
        for (Object[] r : captureRepository.flaggedApplicationsInPool(postingId)) {
            flagged.put(((Number) r[0]).longValue(), ((Number) r[1]).longValue());
        }

        Map<Long, StudentRow> students = flagged.isEmpty() ? Map.of() : students(flagged.values());

        // Narrow to the reviewer's colleges. The rest are counted, so a
        // coordinator knows the drive is also waiting on someone else.
        List<Long> visible = new ArrayList<>();
        int outside = 0;
        for (Map.Entry<Long, Long> e : flagged.entrySet()) {
            StudentRow s = students.get(e.getValue());
            if (policy.canReviewCollege(actor, s == null ? null : s.collegeId())) {
                visible.add(e.getKey());
            } else {
                outside++;
            }
        }

        Map<Long, ApplicationCodingReview> decisions = new HashMap<>();
        Map<Long, String> deciderNames = Map.of();
        if (!flagged.isEmpty()) {
            reviewRepository.findByApplicationIdIn(flagged.keySet())
                    .forEach(r -> decisions.put(r.getApplicationId(), r));
            deciderNames = names(decisions.values().stream()
                    .map(ApplicationCodingReview::getDecidedBy).collect(Collectors.toSet()));
        }
        int awaiting = (int) flagged.keySet().stream().filter(id -> !decisions.containsKey(id)).count();

        List<Item> items = visible.isEmpty() ? List.of()
                : items(visible, flagged, students, decisions, deciderNames);

        int direct = (int) decisions.values().stream()
                .filter(d -> d.getDecision() == CodingReviewDecision.SHORTLIST_DIRECTLY).count();
        int removed = (int) decisions.values().stream()
                .filter(d -> d.getDecision() == CodingReviewDecision.REMOVE).count();
        Integer target = posting.getMaxApplications();

        log.debug("Coding review read: postingId={} actorId={} flagged={} visible={} awaiting={}",
                postingId, actor.userId(), flagged.size(), visible.size(), awaiting);

        return new CodingReviewResponse(
                postingId,
                posting.getCompany().getName(),
                posting.getTitle(),
                gate == null ? "NOT_SEALED" : gate.getState().name(),
                gate == null ? null : gate.getReadyAt(),
                gate == null ? 0 : gate.getPoolSize(),
                gate == null ? 0 : gate.getPendingCount(),
                target,
                direct,
                removed,
                target == null ? null : Math.max(0, target - direct),
                awaiting,
                outside,
                items);
    }

    // -------------------------------------------------------------- decide

    /**
     * Record one decision for the given applications.
     *
     * All or nothing: if any application belongs to a student outside the
     * reviewer's colleges, the whole request is refused rather than half applied,
     * so "decide all" never quietly decides for someone else's students.
     *
     * One transaction for the decision, the gate settling and the refreshed read,
     * so the screen that comes back is exactly what was committed. (It also has to
     * be: review() is called on this instance, which bypasses the transactional
     * proxy, so it runs in this transaction or in none.)
     */
    @Transactional
    public CodingReviewResponse decide(UserPrincipal actor, Long postingId, CodingReviewDecisionRequest request) {
        authorisedPosting(actor, postingId);
        Set<Long> ids = Set.copyOf(request.applicationIds());

        if (!policy.isTrustWide(actor)) {
            List<Long> forbidden = studentCollegesOf(ids).entrySet().stream()
                    .filter(e -> !policy.canReviewCollege(actor, e.getValue()))
                    .map(Map.Entry::getKey).toList();
            if (!forbidden.isEmpty()) {
                log.warn("Coding review decision refused: actorId={} postingId={} outside scope={}",
                        actor.userId(), postingId, forbidden);
                throw new DriveExceptions.ScopeViolationException();
            }
        }

        ScoringGateState state = scoringGateService.resolveReview(
                postingId, ids, request.decision(), request.reason(), actor.userId());
        log.info("Coding review decided: postingId={} actorId={} applications={} decision={} gate={}",
                postingId, actor.userId(), ids.size(), request.decision(), state);
        return review(actor, postingId);
    }

    // ------------------------------------------------------------- helpers

    private JobPosting authorisedPosting(UserPrincipal actor, Long postingId) {
        JobPosting posting = postingRepository.findById(postingId)
                .filter(p -> !p.isDeleted())
                .orElseThrow(() -> new DriveExceptions.DriveNotVisibleException(postingId));
        List<Long> colleges = targetRepository.findByPosting_IdAndIsDeletedFalse(postingId).stream()
                .map(PostingTarget::getCollegeId).distinct().toList();
        if (!policy.canOpenReview(actor, colleges)) {
            throw new DriveExceptions.ScopeViolationException();
        }
        return posting;
    }

    private record StudentRow(Long userId, String name, String email, Long profileId,
                              Integer batchYear, Double cgpa, Integer activeBacklogs, Long collegeId) {}

    private Map<Long, StudentRow> students(Collection<Long> userIds) {
        Map<Long, StudentRow> out = new HashMap<>();
        jdbc.query("""
                SELECT u.id, u.first_name, u.last_name, u.email, sp.id AS profile_id,
                       sp.graduation_year, sp.cgpa, sp.active_backlogs, ura.college_id
                  FROM users u
                  LEFT JOIN student_profiles sp ON sp.user_id = u.id
                  LEFT JOIN user_role_assignments ura
                         ON ura.user_id = u.id AND ura.role = 'STUDENT' AND ura.is_active = true
                 WHERE u.id IN (:ids)
                """, new MapSqlParameterSource("ids", userIds), rs -> {
            long id = rs.getLong("id");
            String name = ((rs.getString("first_name") == null ? "" : rs.getString("first_name")) + " "
                    + (rs.getString("last_name") == null ? "" : rs.getString("last_name"))).trim();
            out.putIfAbsent(id, new StudentRow(id, name, rs.getString("email"),
                    (Long) rs.getObject("profile_id", Long.class),
                    (Integer) rs.getObject("graduation_year", Integer.class),
                    (Double) rs.getObject("cgpa", Double.class),
                    (Integer) rs.getObject("active_backlogs", Integer.class),
                    (Long) rs.getObject("college_id", Long.class)));
        });
        return out;
    }

    private Map<Long, Long> studentCollegesOf(Collection<Long> applicationIds) {
        Map<Long, Long> out = new HashMap<>();
        jdbc.query("""
                SELECT a.id, ura.college_id
                  FROM job_applications a
                  LEFT JOIN user_role_assignments ura
                         ON ura.user_id = a.student_user_id AND ura.role = 'STUDENT' AND ura.is_active = true
                 WHERE a.id IN (:ids)
                """, new MapSqlParameterSource("ids", applicationIds),
                rs -> { out.put(rs.getLong(1), (Long) rs.getObject(2, Long.class)); });
        applicationIds.forEach(id -> out.putIfAbsent(id, null));
        return out;
    }

    private Map<Long, String> names(Set<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> out = new HashMap<>();
        jdbc.query("SELECT id, first_name, last_name FROM users WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", userIds),
                rs -> { out.put(rs.getLong(1), ((rs.getString(2) == null ? "" : rs.getString(2)) + " "
                        + (rs.getString(3) == null ? "" : rs.getString(3))).trim()); });
        return out;
    }

    private List<Item> items(List<Long> applicationIds, Map<Long, Long> flagged,
                             Map<Long, StudentRow> students, Map<Long, ApplicationCodingReview> decisions,
                             Map<Long, String> deciderNames) {
        Set<Long> userIds = applicationIds.stream().map(flagged::get).collect(Collectors.toSet());
        Set<Long> profileIds = userIds.stream().map(students::get)
                .filter(s -> s != null && s.profileId() != null)
                .map(StudentRow::profileId).collect(Collectors.toSet());

        Map<Long, List<CaptureView>> captures = new HashMap<>();
        for (ApplicationCodingCapture c : captureRepository.findLiveByApplicationIdIn(applicationIds)) {
            captures.computeIfAbsent(c.getApplicationId(), k -> new ArrayList<>()).add(new CaptureView(
                    c.getPlatform().name(), c.getHandle(), c.getState().name(), c.getOutcome(),
                    c.getOutcomeDetail(), c.getReviewState().name(), c.getRequestedAt(),
                    c.getSettledAt(), c.getSnapshotId(),
                    c.getFlagReason() == null ? null : c.getFlagReason().name(),
                    c.getRecheckCount(), c.getPreviousHandle()));
        }

        Map<Long, List<SnapshotView>> earlier = earlierData(userIds);
        Map<Long, List<CodingProfileView>> coding = byProfile(profileIds, """
                SELECT student_profile_id, platform, username, profile_link, rating
                  FROM student_coding_profiles WHERE student_profile_id IN (:ids) ORDER BY id
                """, rs -> new CodingProfileView(CodingPlatform.fromLabel(rs.getString("platform")).name(),
                rs.getString("username"), rs.getString("profile_link"),
                (Double) rs.getObject("rating", Double.class)));
        Map<Long, List<ProjectView>> projects = byProfile(profileIds, """
                SELECT student_profile_id, title, technologies, project_link, description
                  FROM student_projects WHERE student_profile_id IN (:ids) ORDER BY id
                """, rs -> new ProjectView(rs.getString("title"), rs.getString("technologies"),
                rs.getString("project_link"), rs.getString("description")));
        Map<Long, List<ExperienceView>> experience = byProfile(profileIds, """
                SELECT student_profile_id, company, role, duration
                  FROM student_work_experiences WHERE student_profile_id IN (:ids) ORDER BY id
                """, rs -> new ExperienceView(rs.getString("company"), rs.getString("role"), rs.getString("duration")));
        Map<Long, List<CertificationView>> certs = byProfile(profileIds, """
                SELECT student_profile_id, name, issuer, verification_status, credential_url
                  FROM student_certifications WHERE student_profile_id IN (:ids) ORDER BY id
                """, rs -> new CertificationView(rs.getString("name"), rs.getString("issuer"),
                rs.getString("verification_status"), rs.getString("credential_url")));

        List<Item> items = new ArrayList<>(applicationIds.size());
        for (Long applicationId : applicationIds) {
            Long userId = flagged.get(applicationId);
            StudentRow s = students.get(userId);
            Long pid = s == null ? null : s.profileId();
            ApplicationCodingReview d = decisions.get(applicationId);
            items.add(new Item(
                    applicationId, userId,
                    s == null ? null : s.name(), s == null ? null : s.email(),
                    s == null ? null : s.collegeId(), s == null ? null : s.batchYear(),
                    s == null ? null : s.cgpa(), s == null ? null : s.activeBacklogs(),
                    captures.getOrDefault(applicationId, List.of()),
                    earlier.getOrDefault(userId, List.of()),
                    coding.getOrDefault(pid, List.of()),
                    projects.getOrDefault(pid, List.of()),
                    experience.getOrDefault(pid, List.of()),
                    certs.getOrDefault(pid, List.of()),
                    d == null ? null : new DecisionView(d.getDecision().name(), d.getReason(),
                            d.getDecidedBy(), deciderNames.get(d.getDecidedBy()), d.getDecidedAt())));
        }
        return items;
    }

    /**
     * The newest successful read we hold per student and platform, from any time --
     * a profile-save scrape, an earlier application, anything. This is the "earlier
     * coding scores" the reviewer weighs; its date is shown with it, because a read
     * from last week and one from last year are different evidence.
     */
    private Map<Long, List<SnapshotView>> earlierData(Set<Long> userIds) {
        Map<Long, List<SnapshotView>> out = new HashMap<>();
        TypeReference<Map<String, Object>> type = new TypeReference<>() {};
        jdbc.query("""
                SELECT DISTINCT ON (student_user_id, platform)
                       student_user_id, platform, handle, scraped_at, metrics::text AS metrics
                  FROM coding_profile_snapshots
                 WHERE student_user_id IN (:ids) AND status = 'OK'
                 ORDER BY student_user_id, platform, scraped_at DESC, id DESC
                """, new MapSqlParameterSource("ids", userIds), rs -> {
            Map<String, Object> metrics = Map.of();
            String json = rs.getString("metrics");
            if (json != null) {
                try {
                    metrics = new LinkedHashMap<>(objectMapper.readValue(json, type));
                    metrics.values().removeIf(v -> v == null
                            || (v instanceof Collection<?> c && c.isEmpty())
                            || (v instanceof Map<?, ?> m && m.isEmpty()));
                } catch (Exception e) {
                    log.warn("Unreadable snapshot metrics for studentUserId={}: {}",
                            rs.getLong("student_user_id"), e.getMessage());
                }
            }
            Timestamp at = rs.getTimestamp("scraped_at");
            out.computeIfAbsent(rs.getLong("student_user_id"), k -> new ArrayList<>()).add(new SnapshotView(
                    rs.getString("platform"), rs.getString("handle"),
                    at == null ? null : at.toInstant(), metrics));
        });
        return out;
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(java.sql.ResultSet rs) throws java.sql.SQLException;
    }

    private <T> Map<Long, List<T>> byProfile(Set<Long> profileIds, String sql, RowMapper<T> mapper) {
        Map<Long, List<T>> out = new HashMap<>();
        if (profileIds.isEmpty()) {
            return out;
        }
        jdbc.query(sql, new MapSqlParameterSource("ids", profileIds), rs -> {
            out.computeIfAbsent(rs.getLong("student_profile_id"), k -> new ArrayList<>()).add(mapper.map(rs));
        });
        return out;
    }
}
