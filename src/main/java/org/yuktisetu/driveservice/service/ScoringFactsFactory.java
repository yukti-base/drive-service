package org.yuktisetu.driveservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.yuktisetu.drive.model.CodingActivityPolicy;
import org.yuktisetu.drive.model.CodingMetrics;
import org.yuktisetu.drive.model.CodingProficiency;
import org.yuktisetu.drive.model.CompositeScorer;
import org.yuktisetu.drive.model.FactorValue;
import org.yuktisetu.drive.model.ScoringFactor;
import org.yuktisetu.drive.model.ScoringFacts;
import org.yuktisetu.profile.model.CodingPlatform;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds each pool member's ScoringFacts from the database, for the factors a
 * drive weights. Every read is one query over the whole pool, never one per
 * student.
 *
 * v1 resolves two factors (decided 2026-10-20):
 *
 *   ACADEMIC_PERFORMANCE  the aggregate CGPA on the student's profile -- the same
 *                         column the eligibility gate reads.
 *   CODING_PROFICIENCY    the snapshots captured at apply time, judged on the
 *                         student's strongest platform by percentile, with cohort
 *                         removal (CodingProficiency). Only the platforms we read
 *                         are ranked -- Codeforces and LeetCode; CodeChef was
 *                         dropped on 2026-10-28 because its terms forbid
 *                         automated access, and snapshots kept from before never
 *                         count. A student whose listed profiles are ALL on
 *                         platforms we do not rank is "not measured" on coding,
 *                         never "none": the gap is ours, not theirs.
 *
 * The other three factors cannot be weighted above zero yet, so nothing here
 * supplies them.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScoringFactsFactory {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /** The platforms that are ranked: by default exactly the ones that are read. */
    @Value("${yuktisetu.coding.ranked.platforms:${yuktisetu.coding.capture.platforms:LEETCODE,CODEFORCES}}")
    private String rankedPlatformsCsv;

    private Set<CodingPlatform> rankedPlatforms() {
        Set<CodingPlatform> out = EnumSet.noneOf(CodingPlatform.class);
        for (String p : rankedPlatformsCsv.split(",")) {
            if (!p.isBlank()) {
                out.add(CodingPlatform.valueOf(p.trim()));
            }
        }
        return out;
    }

    /** One student's coding outcome, kept for the breakdown they are shown. */
    public record CodingDetail(
            String platform,
            BigDecimal platformPercentile,
            int cohortSize,
            Map<String, BigDecimal> considered,
            List<String> notCounted) {}

    public record Built(
            List<ScoringFacts> facts,
            Map<Long, CodingDetail> coding,
            Map<CodingPlatform, String> codingDropped,
            boolean codingFellBack) {}

    /**
     * @param pool applicationId -> studentUserId, for the sealed pool
     */
    public Built build(Long postingId, Map<Long, Long> pool, Set<ScoringFactor> weighted) {
        Collection<Long> students = pool.values();
        Map<Long, Map<ScoringFactor, FactorValue>> values = new LinkedHashMap<>();
        students.forEach(s -> values.put(s, new EnumMap<>(ScoringFactor.class)));

        if (weighted.contains(ScoringFactor.ACADEMIC_PERFORMANCE)) {
            academic(students, values);
        }
        Map<Long, CodingDetail> coding = Map.of();
        Map<CodingPlatform, String> dropped = Map.of();
        boolean fellBack = false;
        if (weighted.contains(ScoringFactor.CODING_PROFICIENCY)) {
            CodingResult r = coding(postingId, pool, values);
            coding = r.detail;
            dropped = r.dropped;
            fellBack = r.fellBack;
        }

        List<ScoringFacts> facts = new ArrayList<>(values.size());
        values.forEach((s, v) -> facts.add(new ScoringFacts(s, v)));
        return new Built(facts, coding, dropped, fellBack);
    }

    // ---------------------------------------------------------------- academic

    private void academic(Collection<Long> students, Map<Long, Map<ScoringFactor, FactorValue>> values) {
        Map<Long, Double> cgpa = new HashMap<>();
        jdbc.query("SELECT user_id, cgpa FROM student_profiles WHERE user_id IN (:ids)",
                new MapSqlParameterSource("ids", students),
                rs -> { cgpa.put(rs.getLong(1), (Double) rs.getObject(2, Double.class)); });
        for (Long s : students) {
            Double v = cgpa.get(s);
            values.get(s).put(ScoringFactor.ACADEMIC_PERFORMANCE, v == null
                    // Eligibility normally refuses a missing CGPA long before here.
                    // If one slips through it is our data gap, not a zero.
                    ? new FactorValue.Unmeasurable("no CGPA on the student's profile")
                    : new FactorValue.Measured(BigDecimal.valueOf(v)));
        }
    }

    // ------------------------------------------------------------------ coding

    private record CodingResult(Map<Long, CodingDetail> detail, Map<CodingPlatform, String> dropped,
                                boolean fellBack) {}

    private CodingResult coding(Long postingId, Map<Long, Long> pool,
                                Map<Long, Map<ScoringFactor, FactorValue>> values) {
        CodingActivityPolicy policy = CodingActivityPolicy.withDefaults();
        Set<CodingPlatform> ranked = rankedPlatforms();

        // The snapshots captured for THIS drive's applications. A student who
        // applied with no coding profile has no captures at all.
        Map<Long, Map<CodingPlatform, CodingMetrics>> metrics = new HashMap<>();
        Set<Long> withCaptures = new java.util.HashSet<>();
        jdbc.query("""
                SELECT c.student_user_id, c.state, s.platform, s.metrics
                  FROM application_coding_captures c
                  LEFT JOIN coding_profile_snapshots s ON s.id = c.snapshot_id AND s.status = 'OK'
                 WHERE c.posting_id = :postingId
                   AND c.application_id IN (:apps)
                   AND c.state <> 'SUPERSEDED'
                """, new MapSqlParameterSource()
                        .addValue("postingId", postingId)
                        .addValue("apps", pool.keySet()),
                rs -> {
                    long student = rs.getLong(1);
                    withCaptures.add(student);
                    String platform = rs.getString(3);
                    String json = rs.getString(4);
                    if ("CAPTURED".equals(rs.getString(2)) && platform != null && json != null
                            && ranked.contains(CodingPlatform.fromLabel(platform))) {
                        metrics.computeIfAbsent(student, k -> new EnumMap<>(CodingPlatform.class))
                                .put(CodingPlatform.fromLabel(platform), parseMetrics(json));
                    }
                });

        Map<Long, Map<CodingPlatform, BigDecimal>> strength = new LinkedHashMap<>();
        Map<Long, List<String>> notCounted = new HashMap<>();
        for (Long s : pool.values()) {
            Map<CodingPlatform, CodingMetrics> mine = metrics.getOrDefault(s, Map.of());
            Map<CodingPlatform, BigDecimal> qualifying = new EnumMap<>(CodingPlatform.class);
            mine.forEach((p, m) -> {
                policy.strength(p, m).ifPresentOrElse(
                        v -> qualifying.put(p, v),
                        () -> policy.rejectionReason(p, m).ifPresent(
                                why -> notCounted.computeIfAbsent(s, k -> new ArrayList<>()).add(why)));
            });
            if (!qualifying.isEmpty()) {
                strength.put(s, qualifying);
            }
        }

        CodingProficiency.Outcome outcome =
                CodingProficiency.resolve(strength, CompositeScorer.DEFAULT_MIN_SAMPLE);

        // Platforms each student listed on their profile that we do not rank.
        Map<Long, Set<CodingPlatform>> unranked = new HashMap<>();
        jdbc.query("""
                SELECT sp.user_id, scp.platform
                  FROM student_coding_profiles scp
                  JOIN student_profiles sp ON sp.id = scp.student_profile_id
                 WHERE sp.user_id IN (:students)
                   AND coalesce(trim(scp.username), '') <> ''
                """, new MapSqlParameterSource("students", pool.values()), rs -> {
            CodingPlatform p = parsePlatform(rs.getString(2));
            if (p != null && !ranked.contains(p)) {
                unranked.computeIfAbsent(rs.getLong(1), k -> EnumSet.noneOf(CodingPlatform.class)).add(p);
            }
        });

        Map<Long, CodingDetail> detail = new HashMap<>();
        for (Long s : pool.values()) {
            CodingProficiency.Assignment a = outcome.assignments().get(s);
            FactorValue v;
            if (a != null) {
                v = new FactorValue.Measured(a.percentile());
                Map<String, BigDecimal> considered = new LinkedHashMap<>();
                a.consideredPercentiles().forEach((p, pct) -> considered.put(p.label(), pct));
                detail.put(s, new CodingDetail(a.platform().label(), a.percentile(), a.cohortSize(),
                        considered, notCounted.getOrDefault(s, List.of())));
            } else if (outcome.unresolved().contains(s)) {
                // They have real activity, on platforms too few applicants share
                // for a percentile. Our gap, not theirs: the TnP decides.
                v = new FactorValue.Unmeasurable(
                        "the platforms this student uses had too few applicants on this drive to compare");
                detail.put(s, new CodingDetail(null, null, 0, Map.of(), notCounted.getOrDefault(s, List.of())));
            } else if (unranked.containsKey(s)) {
                // Nothing readable that counts, but they listed a profile on a
                // platform we do not rank (CodeChef, HackerRank...). Our gap, not
                // theirs: left out and rescaled, and the TnP decides (2026-10-28).
                String names = unranked.get(s).stream().map(CodingPlatform::label).sorted()
                        .reduce((x, y) -> x + ", " + y).orElse("");
                v = new FactorValue.Unmeasurable("only listed coding profiles on platforms that are not ranked ("
                        + names + ")");
                List<String> why = new ArrayList<>(notCounted.getOrDefault(s, List.of()));
                why.add(names + ": not ranked (we do not read this platform)");
                detail.put(s, new CodingDetail(null, null, 0, Map.of(), why));
            } else {
                // No profile, or nothing above the activity bar: measured, and the
                // honest answer is none. Ranks at the bottom of the factor.
                v = new FactorValue.MeasuredNone();
                List<String> why = new ArrayList<>(notCounted.getOrDefault(s, List.of()));
                if (!withCaptures.contains(s)) {
                    why.add("no coding profile was listed when applying");
                }
                detail.put(s, new CodingDetail(null, null, 0, Map.of(), why));
            }
            values.get(s).put(ScoringFactor.CODING_PROFICIENCY, v);
        }

        if (!outcome.droppedPlatforms().isEmpty() || outcome.fellBackToFullCohorts()) {
            log.info("Coding cohorts for postingId={}: dropped={} fellBackToFullCohorts={}",
                    postingId, outcome.droppedPlatforms(), outcome.fellBackToFullCohorts());
        }
        return new CodingResult(detail, outcome.droppedPlatforms(), outcome.fellBackToFullCohorts());
    }

    private static CodingPlatform parsePlatform(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return CodingPlatform.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            try {
                return CodingPlatform.fromLabel(raw.trim());
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }

    /**
     * The scraper writes snake_case (yuktisetu_scraper.models.Metrics). Every
     * field stays null when absent; nothing here may default to zero.
     */
    CodingMetrics parseMetrics(String json) {
        JsonNode n = objectMapper.readTree(json);
        Map<String, Integer> topics = new HashMap<>();
        JsonNode t = n.get("topics");
        if (t != null && t.isObject()) {
            t.properties().forEach(e -> {
                if (e.getValue().isNumber()) {
                    topics.put(e.getKey(), e.getValue().intValue());
                }
            });
        }
        List<String> languages = new ArrayList<>();
        JsonNode l = n.get("languages");
        if (l != null && l.isArray()) {
            l.forEach(x -> languages.add(x.asString()));
        }
        JsonNode top = n.get("top_percent");
        return new CodingMetrics(
                intOrNull(n, "rating"), intOrNull(n, "max_rating"), intOrNull(n, "contests_attended"),
                intOrNull(n, "solved_total"), intOrNull(n, "solved_easy"), intOrNull(n, "solved_medium"),
                intOrNull(n, "solved_hard"), intOrNull(n, "global_rank"),
                top == null || top.isNull() ? null : top.decimalValue(),
                intOrNull(n, "stars"), languages, topics);
    }

    private static Integer intOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() || !v.isNumber() ? null : v.intValue();
    }
}
