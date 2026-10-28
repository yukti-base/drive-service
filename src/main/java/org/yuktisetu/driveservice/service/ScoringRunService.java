package org.yuktisetu.driveservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.drive.db.ApplicationScore;
import org.yuktisetu.drive.db.DriveScoreRun;
import org.yuktisetu.drive.db.DriveScoringGate;
import org.yuktisetu.drive.db.JobPosting;
import org.yuktisetu.drive.db.PostingScoringConfig;
import org.yuktisetu.drive.model.CompositeScorer;
import org.yuktisetu.drive.model.FactorValue;
import org.yuktisetu.drive.model.ScoreRunStatus;
import org.yuktisetu.drive.model.ScoringFactor;
import org.yuktisetu.drive.model.ScoringGateState;
import org.yuktisetu.drive.model.ScoringTrace;
import org.yuktisetu.drive.model.ShortlistingMode;
import org.yuktisetu.drive.model.WeightProfile;
import org.yuktisetu.drive.repository.ApplicationScoreRepository;
import org.yuktisetu.drive.repository.DriveScoreRunRepository;
import org.yuktisetu.drive.repository.DriveScoringGateRepository;
import org.yuktisetu.drive.repository.JobApplicationRepository;
import org.yuktisetu.drive.repository.JobPostingRepository;
import org.yuktisetu.drive.repository.PostingScoringConfigRepository;
import org.yuktisetu.driveservice.exception.DriveExceptions;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The scoring engine: turns a READY drive into stored composite scores.
 *
 * Triggered by DriveReadyForScoring (and by the reconciler for a drive whose
 * event never arrived). One drive is one transaction, under the scoring gate's
 * lock -- the first lock every gate path takes -- so the consumer and the
 * reconciler can never score the same drive twice, and a crash mid-run leaves no
 * half-scored drive behind: the next attempt finds no run and starts clean.
 *
 * Nothing here scrapes or calls out. Every input -- snapshots captured at apply
 * time, CGPAs, the drive's weights -- is already in the database, so a re-run
 * reproduces the same numbers exactly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScoringRunService {

    private final DriveScoringGateRepository gateRepository;
    private final DriveScoreRunRepository runRepository;
    private final ApplicationScoreRepository scoreRepository;
    private final PostingScoringConfigRepository configRepository;
    private final JobPostingRepository postingRepository;
    private final JobApplicationRepository applicationRepository;
    private final ScoringFactsFactory factsFactory;
    private final ObjectMapper objectMapper;

    /**
     * Score one READY drive, once. Idempotent: a drive that already has a run is
     * left alone, which is what makes Kafka redelivery and the reconciler safe.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void run(String readyEventId, Long postingId) {
        DriveScoringGate gate = gateRepository.lockByPostingId(postingId).orElse(null);
        if (gate == null || gate.getState() != ScoringGateState.READY) {
            log.warn("Scoring skipped: postingId={} gate={} is not READY", postingId,
                    gate == null ? "missing" : gate.getState());
            return;
        }
        if (runRepository.findByPostingIdAndSupersededAtIsNull(postingId).isPresent()) {
            log.debug("Scoring skipped: postingId={} already has a run (eventId={})", postingId, readyEventId);
            return;
        }
        JobPosting posting = postingRepository.findById(postingId).orElse(null);
        if (posting == null || posting.getShortlistingMode() != ShortlistingMode.COMPOSITE_SCORE) {
            return;
        }
        DriveScoreRun run = new DriveScoreRun();
        run.setPostingId(postingId);
        run.setReadyEventId(readyEventId);
        compute(run, posting, false);
    }

    /**
     * The TnP has looked at the students the run was held for and says proceed.
     * The run is redone in place with unmeasured factors rescaled away for those
     * students, exactly as the scorer would for an outage.
     */
    @Transactional
    public DriveScoreRun approveHeld(Long postingId, Long actorUserId) {
        gateRepository.lockByPostingId(postingId)
                .orElseThrow(() -> new DriveExceptions.ScoringStateException("NOT_SCORED",
                        "This drive has not been scored."));
        DriveScoreRun run = runRepository.findByPostingIdAndSupersededAtIsNull(postingId)
                .orElseThrow(() -> new DriveExceptions.ScoringStateException("NOT_SCORED",
                        "This drive has not been scored."));
        if (run.getStatus() != ScoreRunStatus.HELD_FOR_REVIEW) {
            throw new DriveExceptions.ScoringStateException("SCORE_RUN_NOT_HELD",
                    "This drive's scoring is not waiting for approval.");
        }
        JobPosting posting = postingRepository.findById(postingId).orElseThrow();
        if (posting.getShortlistPublishedAt() != null) {
            throw new DriveExceptions.ScoringStateException("SHORTLIST_PUBLISHED",
                    "The shortlist for this drive has already been published.");
        }
        run.setApprovedBy(actorUserId);
        run.setApprovedAt(Instant.now());
        compute(run, posting, true);
        log.info("Held scoring approved: postingId={} actorId={} status={}", postingId, actorUserId, run.getStatus());
        return run;
    }

    /** READY drives with no run: their event was lost. Scored from the gate row. */
    @Transactional(readOnly = true)
    public List<Object[]> readyWithoutRun() {
        return runRepository.findReadyWithoutRun();
    }

    // ------------------------------------------------------------------ core

    private void compute(DriveScoreRun run, JobPosting posting, boolean approved) {
        Long postingId = posting.getId();
        Instant now = Instant.now();

        Map<Long, Long> pool = new LinkedHashMap<>();
        for (Object[] r : applicationRepository.findScoringPool(postingId)) {
            pool.put(((Number) r[0]).longValue(), ((Number) r[1]).longValue());
        }
        run.setPoolSize(pool.size());
        run.setScoredAt(now);
        run.setScoredCount(0);
        run.setHeldCount(0);
        run.setDeclinedReason(null);
        run.setHeldStudents(null);

        Optional<PostingScoringConfig> cfg = configRepository.findByPostingId(postingId);
        if (cfg.isEmpty() || cfg.get().weights().values().stream().allMatch(w -> w == null || w.signum() == 0)) {
            // A drive published before weights existed. Nothing to score on, and
            // inventing weights is exactly what the design forbids.
            run.setConfig("{}");
            finish(run, ScoreRunStatus.DECLINED,
                    "No scoring weights were set for this drive. Shortlist it manually.");
            return;
        }
        PostingScoringConfig config = cfg.get();
        WeightProfile weights = config.toWeightProfile();
        run.setConfig(write(configSnapshot(config, weights)));

        if (run.getId() != null) {
            scoreRepository.deleteByRunId(run.getId());
        }

        ScoringFactsFactory.Built built = factsFactory.build(postingId, pool, weights.weightedFactors());
        run.setCodingDropped(built.codingDropped().isEmpty() ? null : write(
                built.codingDropped().entrySet().stream().collect(Collectors.toMap(
                        e -> e.getKey().label(), Map.Entry::getValue))));

        CompositeScorer.Config scorerConfig = new CompositeScorer.Config(
                weights, config.getThreshold(), config.floors(),
                CompositeScorer.DEFAULT_MIN_SAMPLE, CompositeScorer.DEFAULT_MIN_POOL, approved);
        CompositeScorer.Result result = CompositeScorer.score(built.facts(), scorerConfig);
        run.setPoolExclusions(result.poolWideExclusions().isEmpty() ? null : write(result.poolWideExclusions()));

        if (result.wasDeclined()) {
            finish(run, ScoreRunStatus.DECLINED, result.declined());
            log.info("Scoring declined: postingId={} pool={} reason={}", postingId, pool.size(), result.declined());
            return;
        }
        if (result.heldForReview()) {
            run.setHeldCount(result.needsTnpReview().size());
            run.setHeldStudents(write(result.needsTnpReview()));
            finish(run, ScoreRunStatus.HELD_FOR_REVIEW, null);
            log.info("Scoring held for TnP review: postingId={} pool={} held={}",
                    postingId, pool.size(), result.needsTnpReview().size());
            return;
        }

        run.setStatus(ScoreRunStatus.SCORED);
        run = runRepository.saveAndFlush(run);

        Map<Long, Long> applicationOf = new HashMap<>();
        pool.forEach((app, student) -> applicationOf.put(student, app));
        Map<Integer, Long> rankCounts = result.ranks().values().stream()
                .collect(Collectors.groupingBy(r -> r, Collectors.counting()));

        List<ApplicationScore> rows = new ArrayList<>(result.ranked().size());
        for (ScoringTrace t : result.ranked()) {
            int rank = result.ranks().get(t.studentUserId());
            ApplicationScore s = new ApplicationScore();
            s.setApplicationId(applicationOf.get(t.studentUserId()));
            s.setPostingId(postingId);
            s.setRunId(run.getId());
            s.setStudentUserId(t.studentUserId());
            s.setComposite(t.composite());
            s.setMeetsThreshold(t.meetsThreshold());
            s.setShortlistEligible(t.shortlistEligible());
            s.setFloorsFailed(t.floorsFailed().isEmpty() ? null
                    : t.floorsFailed().stream().map(Enum::name).collect(Collectors.joining(",")));
            s.setWeightsRenormalised(t.weightsRenormalised());
            s.setRank(rank);
            s.setTied(rankCounts.getOrDefault(rank, 1L) > 1);
            s.setBreakdown(write(breakdown(t, built.coding().get(t.studentUserId()))));
            rows.add(s);
            log.debug("Score postingId={} studentUserId={} rank={} composite={} meets={} floorsFailed={} trace={}",
                    postingId, t.studentUserId(), rank, t.composite(), t.meetsThreshold(),
                    t.floorsFailed(), t.factors());
        }
        scoreRepository.saveAll(rows);
        run.setScoredCount(rows.size());
        runRepository.save(run);

        long eligible = rows.stream().filter(ApplicationScore::isShortlistEligible).count();
        log.info("Drive scored: postingId={} pool={} scored={} shortlistEligible={} approved={} weights={}",
                postingId, pool.size(), rows.size(), eligible, approved, weights.weights());
    }

    private void finish(DriveScoreRun run, ScoreRunStatus status, String declinedReason) {
        run.setStatus(status);
        run.setDeclinedReason(declinedReason);
        runRepository.save(run);
    }

    /** The weights as the TnP typed them AND as shares of 100, so a student sees both. */
    private Map<String, Object> configSnapshot(PostingScoringConfig config, WeightProfile weights) {
        Map<String, Object> w = new LinkedHashMap<>();
        Map<ScoringFactor, BigDecimal> shares = weights.sharesOver(weights.weightedFactors());
        for (ScoringFactor f : weights.weightedFactors()) {
            w.put(f.name(), Map.of("raw", weights.rawWeightOf(f), "share", shares.get(f)));
        }
        Map<String, Object> floors = new LinkedHashMap<>();
        config.floors().forEach((f, v) -> floors.put(f.name(), v));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("weights", w);
        out.put("threshold", config.getThreshold());
        out.put("floors", floors);
        return out;
    }

    /**
     * The stored breakdown: the scorer's own trace, flattened to plain JSON.
     * FactorValue is a sealed type with no JSON form of its own, so its kind is
     * written out explicitly.
     */
    private Map<String, Object> breakdown(ScoringTrace t, ScoringFactsFactory.CodingDetail coding) {
        List<Map<String, Object>> factors = new ArrayList<>();
        for (ScoringTrace.FactorTrace f : t.factors()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("factor", f.factor().name());
            m.put("kind", switch (f.value()) {
                case FactorValue.Measured ignored -> "MEASURED";
                case FactorValue.MeasuredNone ignored -> "NONE";
                case FactorValue.Unmeasurable ignored -> "UNMEASURABLE";
            });
            m.put("raw", f.raw());
            m.put("percentile", f.percentile());
            m.put("sampleSize", f.sampleSize());
            m.put("weightApplied", f.weightApplied());
            m.put("contribution", f.contribution());
            m.put("floor", f.floor());
            m.put("floorFailed", f.floorFailed());
            m.put("excludedReason", f.excludedReason());
            factors.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("factors", factors);
        out.put("composite", t.composite());
        out.put("threshold", t.threshold());
        out.put("meetsThreshold", t.meetsThreshold());
        out.put("floorsFailed", t.floorsFailed().stream().map(Enum::name).toList());
        out.put("weightsRenormalised", t.weightsRenormalised());
        if (coding != null) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("platform", coding.platform());
            c.put("platformPercentile", coding.platformPercentile());
            c.put("cohortSize", coding.cohortSize());
            c.put("considered", coding.considered());
            c.put("notCounted", coding.notCounted());
            out.put("coding", c);
        }
        return out;
    }

    private String write(Object value) {
        return objectMapper.writeValueAsString(plain(value));
    }

    /** Enum and Long map keys become strings, at every depth, so the JSON is stable. */
    private static Object plain(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(k instanceof Enum<?> e ? e.name() : String.valueOf(k), plain(v)));
            return out;
        }
        if (value instanceof List<?> l) {
            return l.stream().map(ScoringRunService::plain).toList();
        }
        return value;
    }
}
