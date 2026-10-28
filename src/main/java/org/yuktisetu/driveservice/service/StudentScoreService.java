package org.yuktisetu.driveservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.drive.repository.PlatformSettingsRepository;
import org.yuktisetu.driveservice.dto.ScoreResponses.StudentDriveScore;
import org.yuktisetu.driveservice.dto.ScoreResponses.StudentScoresResponse;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * A student's own composite scores, behind every gate at once.
 *
 * Shown only when ALL of these hold, checked here on the server so turning the
 * switch off hides scores everywhere, whatever a client does:
 *   - the platform switch show_scores_to_students is on;
 *   - the drive uses composite-score shortlisting;
 *   - its shortlist has been published;
 *   - its run was SCORED (a declined drive was shortlisted by hand).
 *
 * The rank is never read here, let alone returned.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudentScoreService {

    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {};

    private final PlatformSettingsRepository settingsRepository;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public StudentScoresResponse myScores(Long studentUserId) {
        if (!settingsRepository.current().isShowScoresToStudents()) {
            return new StudentScoresResponse(false, List.of());
        }
        List<StudentDriveScore> items = new ArrayList<>();
        jdbc.query("""
                SELECT p.id, c.name AS company, p.title, a.status, p.shortlist_published_at,
                       s.composite, s.meets_threshold, s.floors_failed, s.weights_renormalised,
                       s.breakdown, r.config, r.pool_size
                  FROM application_scores s
                  JOIN job_applications a ON a.id = s.application_id AND a.is_deleted = false
                  JOIN job_postings p     ON p.id = s.posting_id AND p.is_deleted = false
                  JOIN companies c        ON c.id = p.company_id
                  JOIN drive_score_runs r ON r.id = s.run_id
                 WHERE s.student_user_id = :student
                   AND p.shortlisting_mode = 'COMPOSITE_SCORE'
                   AND p.shortlist_published_at IS NOT NULL
                   AND r.status = 'SCORED'
                   AND r.superseded_at IS NULL
                 ORDER BY p.shortlist_published_at DESC
                """, new MapSqlParameterSource("student", studentUserId), rs -> {
            Map<String, Object> breakdown = json(rs.getString("breakdown"));
            Map<String, Object> config = json(rs.getString("config"));
            String floors = rs.getString("floors_failed");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> factors = (List<Map<String, Object>>) breakdown.getOrDefault("factors", List.of());
            @SuppressWarnings("unchecked")
            Map<String, Object> coding = (Map<String, Object>) breakdown.get("coding");
            @SuppressWarnings("unchecked")
            Map<String, Object> weights = (Map<String, Object>) config.getOrDefault("weights", Map.of());
            items.add(new StudentDriveScore(
                    rs.getLong("id"), rs.getString("company"), rs.getString("title"), rs.getString("status"),
                    rs.getTimestamp("shortlist_published_at").toInstant(),
                    rs.getBigDecimal("composite"),
                    config.get("threshold") == null ? null : new java.math.BigDecimal(config.get("threshold").toString()),
                    rs.getBoolean("meets_threshold"),
                    floors == null ? List.of() : Arrays.asList(floors.split(",")),
                    rs.getBoolean("weights_renormalised"),
                    factors, coding, weights, rs.getInt("pool_size")));
        });
        log.debug("Student scores read: studentUserId={} drives={}", studentUserId, items.size());
        return new StudentScoresResponse(true, items);
    }

    private Map<String, Object> json(String raw) {
        return raw == null || raw.isBlank() ? Map.of() : objectMapper.readValue(raw, JSON_MAP);
    }
}
