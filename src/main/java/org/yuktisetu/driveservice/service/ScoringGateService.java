package org.yuktisetu.driveservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.drive.db.ApplicationCodingCapture;
import org.yuktisetu.drive.db.ApplicationCodingReview;
import org.yuktisetu.drive.db.DriveScoringGate;
import org.yuktisetu.drive.db.JobPosting;
import org.yuktisetu.drive.model.CaptureFlagReason;
import org.yuktisetu.drive.model.CaptureState;
import org.yuktisetu.drive.model.CodingReviewDecision;
import org.yuktisetu.drive.model.PostingStatus;
import org.yuktisetu.drive.model.ScoringGateState;
import org.yuktisetu.drive.model.ShortlistingMode;
import org.yuktisetu.drive.repository.ApplicationCodingCaptureRepository;
import org.yuktisetu.drive.repository.ApplicationCodingReviewRepository;
import org.yuktisetu.drive.repository.DriveScoringGateRepository;
import org.yuktisetu.drive.repository.JobPostingRepository;
import org.yuktisetu.messaging.events.CodingScrapeCompletedEvent;
import org.yuktisetu.messaging.events.DriveReadyForScoringEvent;
import org.yuktisetu.messaging.events.DriveTopics;
import org.yuktisetu.driveservice.exception.DriveExceptions;
import org.yuktisetu.profile.model.CodingPlatform;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Decides when a sealed COMPOSITE_SCORE drive may be scored, and says so exactly
 * once.
 *
 * NOTHING WAITS. There is no thread watching a drive. A drive becomes ready the
 * moment the last of three conditions is settled, by whichever action settled it:
 *
 *   1. the deadline sealed the pool           -> openAndSettle (in the seal transaction)
 *   2. the last scrape in the pool finished   -> onScrapeCompleted (Kafka consumer)
 *   3. the TnP resolved the last flagged one  -> settle (from the review action)
 *
 * Each of those calls settleLocked, which locks the gate row, recounts, and
 * moves the state forward. Because the gate row is locked first, two of them
 * finishing at the same instant serialise on it, and the second sees the first's
 * work -- so READY, and its DriveReadyForScoring event, happen once and only once.
 *
 * The reconciler is a safety net, not the mechanism: it repairs a capture whose
 * completion event was lost, and catches changes made where no hook exists yet
 * (a disqualification in admin-service). With Kafka healthy it finds nothing to
 * do.
 *
 * STUDENT RE-CHECK. A capture whose handle could not be read waits on the
 * student (AWAITING_STUDENT) until the deadline. The gate never waits on that:
 * it only exists after the seal, and the first thing settleLocked does is flag
 * every capture still waiting on a student (NO_RECHECK). When nothing is pending
 * any more, it also flags applications left with nothing to score on
 * (NOTHING_LEFT). See CaptureOutcomePolicy for how a single result is settled.
 *
 * LOCK ORDER, everywhere: posting row(s), then gate row(s), each in posting-id
 * order, then capture rows. The seal holds its posting exclusively; the event
 * consumer takes it shared; the reconciler starts at the gate, because it only
 * ever runs after the seal has committed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScoringGateService {

    private final DriveScoringGateRepository gateRepository;
    private final ApplicationCodingCaptureRepository captureRepository;
    private final ApplicationCodingReviewRepository reviewRepository;
    private final JobPostingRepository postingRepository;
    private final OutboxWriter outboxWriter;
    private final DriveNotificationService notificationService;

    // ------------------------------------------------------------- condition 1

    /**
     * Open the gate for a just-sealed pool and settle it immediately.
     *
     * Runs inside the seal transaction, so the gate exists if and only if the pool
     * is sealed. A drive whose applicants have no pending captures -- nobody listed
     * a coding platform, or every capture finished long before the deadline -- is
     * therefore READY in the same transaction that sealed it, with no wait at all.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void openAndSettle(JobPosting posting, Instant sealedAt) {
        if (posting.getShortlistingMode() != ShortlistingMode.COMPOSITE_SCORE) {
            return;
        }
        DriveScoringGate gate = gateRepository.findByPostingId(posting.getId()).orElse(null);
        if (gate == null) {
            gate = new DriveScoringGate();
            gate.setPostingId(posting.getId());
            gate.setState(ScoringGateState.COLLECTING);
            gate.setOpenedAt(sealedAt);
            gate = gateRepository.saveAndFlush(gate);
            log.info("Scoring gate opened: postingId={} gateId={}", posting.getId(), gate.getId());
        }
        settleLocked(gate, posting);
    }

    // ------------------------------------------------------------- condition 2

    /**
     * A scrape job ended. Settle every capture waiting on it, then every gate
     * those captures belong to.
     *
     * Idempotent: a redelivered event finds no PENDING captures for the job and
     * does nothing, which is what makes Kafka's at-least-once delivery safe here.
     */
    @Transactional
    public void onScrapeCompleted(String eventId, CodingScrapeCompletedEvent event) {
        List<ApplicationCodingCapture> waiting =
                captureRepository.findByJobIdAndState(event.jobId(), CaptureState.PENDING);
        if (waiting.isEmpty()) {
            log.debug("Scrape completion eventId={} jobId={} has no pending captures; nothing to do",
                    eventId, event.jobId());
            return;
        }

        // Locks in the order every path follows: posting, then gate, then captures.
        // Usually one posting; a few when the student applied to several drives in
        // the same minute and they shared this job.
        TreeMap<Long, Optional<DriveScoringGate>> gates = new TreeMap<>();
        waiting.forEach(c -> gates.put(c.getPostingId(), Optional.empty()));

        // The posting row, SHARED. This is what makes a completion and the deadline
        // seal see each other. The seal holds this row exclusively for its whole
        // transaction, so a completion either finishes before the seal starts
        // (and the seal counts its capture as settled) or waits for the seal to
        // commit (and then finds the gate and settles it). Without it, each could
        // run in the other's blind spot: the seal counting a capture as pending
        // while the completion, not yet seeing the seal's gate, settles the capture
        // and moves on -- a drive left COLLECTING with nothing left to wake it.
        // Shared, so it never blocks applies, which hold the same row shared.
        Map<Long, JobPosting> postings = new HashMap<>();
        for (Long postingId : gates.keySet()) {
            postingRepository.findForApply(postingId).ifPresent(p -> postings.put(postingId, p));
        }
        for (Long postingId : gates.keySet()) {
            gates.put(postingId, gateRepository.lockByPostingId(postingId));
        }

        // Re-read under the locks: another consumer or the reconciler may have
        // settled some of these while we were acquiring them.
        List<ApplicationCodingCapture> captures =
                captureRepository.lockByJobIdAndState(event.jobId(), CaptureState.PENDING);
        Instant now = Instant.now();
        Map<CaptureOutcomePolicy.Notice, List<ApplicationCodingCapture>> byNotice =
                new java.util.EnumMap<>(CaptureOutcomePolicy.Notice.class);
        for (ApplicationCodingCapture c : captures) {
            CaptureOutcomePolicy.Notice n = CaptureOutcomePolicy.settle(c, postings.get(c.getPostingId()),
                    event.captured(), event.snapshotId(), event.outcome(), event.error(), now);
            byNotice.computeIfAbsent(n, k -> new ArrayList<>()).add(c);
        }
        captureRepository.saveAllAndFlush(captures);
        notifyStudent(byNotice, postings);

        log.info("Scrape completion eventId={} jobId={} studentUserId={} platform={} outcome={} settled {} capture(s)",
                eventId, event.jobId(), event.studentUserId(), event.platform(), event.outcome(), captures.size());

        for (Map.Entry<Long, Optional<DriveScoringGate>> e : gates.entrySet()) {
            // No gate means the pool is not sealed yet. The capture is settled and
            // will be counted when the deadline seals the pool.
            e.getValue().ifPresent(gate -> settleLocked(gate, null));
        }
    }

    // ------------------------------------------------------------- condition 3

    /**
     * Repair and recount one drive, moving its gate forward if it can.
     *
     * Called by the reconciler, each drive in its own transaction so one drive's
     * failure never rolls back another's. The TnP review action, when it is built,
     * follows the same shape: lock the gate, resolve its captures, then settle.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reconcile(Long postingId) {
        DriveScoringGate gate = gateRepository.lockByPostingId(postingId).orElse(null);
        if (gate == null || !gate.getState().isOpen()) {
            return;
        }

        // Repair: captures still PENDING although their job has finished. With
        // Kafka healthy this is empty; it exists for an event that was lost.
        List<Object[]> stale = captureRepository.lockSettledButPending(postingId);
        if (!stale.isEmpty()) {
            Map<Long, Object[]> byCapture = new TreeMap<>();
            stale.forEach(r -> byCapture.put(((Number) r[0]).longValue(), r));
            List<ApplicationCodingCapture> captures = captureRepository.findByIdIn(byCapture.keySet());
            JobPosting posting = postingRepository.findById(postingId).orElse(null);
            Instant now = Instant.now();
            // Notices grouped per student and platform, as the consumer would
            // have sent them had the event arrived.
            Map<String, Map<CaptureOutcomePolicy.Notice, List<ApplicationCodingCapture>>> notices = new TreeMap<>();
            for (ApplicationCodingCapture c : captures) {
                Object[] r = byCapture.get(c.getId());
                boolean captured = "DONE".equals(r[1]) && r[3] != null;
                Long snapshotId = r[3] == null ? null : ((Number) r[3]).longValue();
                CaptureOutcomePolicy.Notice n = CaptureOutcomePolicy.settle(
                        c, posting, captured, snapshotId, (String) r[2], (String) r[4], now);
                notices.computeIfAbsent(c.getStudentUserId() + ":" + c.getPlatform(),
                                k -> new java.util.EnumMap<>(CaptureOutcomePolicy.Notice.class))
                        .computeIfAbsent(n, k -> new ArrayList<>()).add(c);
            }
            captureRepository.saveAllAndFlush(captures);
            if (posting != null) {
                Map<Long, JobPosting> postings = Map.of(postingId, posting);
                notices.values().forEach(byNotice -> notifyStudent(byNotice, postings));
            }
            log.warn("Reconciler repaired {} capture(s) for postingId={} whose completion event never arrived",
                    captures.size(), postingId);
        }
        settleLocked(gate, null);
    }

    /**
     * Record the TnP's decision on flagged applications and settle the gate.
     *
     * Same lock order as every other path -- the gate, then captures -- and the
     * same transaction, so the last decision and the drive becoming READY are one
     * commit. Decisions on a READY drive are refused: the engine has already been
     * told to start.
     *
     * The caller has already checked the actor's authority over each student;
     * this method checks only that each application is genuinely flagged on this
     * drive, so a request cannot decide for someone outside the review.
     */
    @Transactional
    public ScoringGateState resolveReview(Long postingId, Collection<Long> applicationIds,
                                          CodingReviewDecision decision, String reason, Long actorUserId) {
        DriveScoringGate gate = gateRepository.lockByPostingId(postingId)
                .orElseThrow(() -> new DriveExceptions.PostingNotReadyException(
                        "This drive has no coding review: its applicant pool has not been sealed."));
        if (!gate.getState().isOpen()) {
            throw new DriveExceptions.ReviewClosedException(gate.getState());
        }

        Map<Long, Long> flagged = new HashMap<>();
        for (Object[] r : captureRepository.flaggedApplicationsInPool(postingId)) {
            flagged.put(((Number) r[0]).longValue(), ((Number) r[1]).longValue());
        }
        List<Long> notFlagged = applicationIds.stream().filter(id -> !flagged.containsKey(id)).toList();
        if (!notFlagged.isEmpty()) {
            throw new DriveExceptions.InvalidRequestException(
                    "These applications are not under coding review on this drive: " + notFlagged);
        }

        Map<Long, ApplicationCodingReview> existing = new HashMap<>();
        reviewRepository.findByApplicationIdIn(applicationIds).forEach(r -> existing.put(r.getApplicationId(), r));

        Instant now = Instant.now();
        List<ApplicationCodingReview> rows = new ArrayList<>(applicationIds.size());
        for (Long applicationId : applicationIds) {
            ApplicationCodingReview r = existing.getOrDefault(applicationId, new ApplicationCodingReview());
            if (r.getId() != null && r.getDecision() != decision) {
                log.info("Coding review changed: applicationId={} {} -> {} by actorId={}",
                        applicationId, r.getDecision(), decision, actorUserId);
            }
            r.setApplicationId(applicationId);
            r.setPostingId(postingId);
            r.setStudentUserId(flagged.get(applicationId));
            r.setDecision(decision);
            r.setReason(reason.trim());
            r.setDecidedBy(actorUserId);
            r.setDecidedAt(now);
            rows.add(r);
        }
        reviewRepository.saveAll(rows);
        int resolved = captureRepository.resolveFlags(applicationIds);

        log.info("Coding review: postingId={} {} application(s) -> {} by actorId={} ({} flag(s) resolved)",
                postingId, applicationIds.size(), decision, actorUserId, resolved);

        settleLocked(gate, null);
        return gate.getState();
    }

    // ---------------------------------------------------------------- the core

    /**
     * Recount under the gate lock and move the state forward. The caller holds
     * the gate row lock and an open transaction.
     */
    private void settleLocked(DriveScoringGate gate, JobPosting knownPosting) {
        if (!gate.getState().isOpen()) {
            return;
        }
        Long postingId = gate.getPostingId();
        JobPosting posting = knownPosting != null ? knownPosting
                : postingRepository.findById(postingId).orElse(null);

        if (posting == null || posting.getStatus() == PostingStatus.CANCELLED) {
            gate.setState(ScoringGateState.CANCELLED);
            gateRepository.save(gate);
            log.info("Scoring gate cancelled: postingId={} (drive cancelled)", postingId);
            return;
        }

        Instant now = Instant.now();

        // The deadline has passed for everyone in a sealed pool, so a capture
        // still waiting on the student's re-check goes to the TnP now.
        escalate(captureRepository.lockAwaitingInPool(postingId), CaptureFlagReason.NO_RECHECK, posting, now);

        // Locked read of what is still pending -- see the repository for why a
        // plain count is not enough here.
        int pending = captureRepository.lockPendingInPool(postingId).size();
        if (pending == 0) {
            // Only now is "nothing left to score on" knowable: every sibling
            // capture has settled.
            escalate(captureRepository.lockNothingLeftInPool(postingId), CaptureFlagReason.NOTHING_LEFT, posting, now);
        }
        Object[] counts = captureRepository.poolCounts(postingId).get(0);
        int poolSize = ((Number) counts[0]).intValue();
        int captured = ((Number) counts[1]).intValue();
        int failed = ((Number) counts[2]).intValue();
        int needsReview = ((Number) counts[3]).intValue();

        gate.setPoolSize(poolSize);
        gate.setPendingCount(pending);
        gate.setCapturedCount(captured);
        gate.setFailedCount(failed);
        gate.setReviewCount(needsReview);

        if (pending > 0) {
            gate.setState(ScoringGateState.COLLECTING);
            gateRepository.save(gate);
            log.debug("Scoring gate postingId={} collecting: pending={} captured={} failed={} pool={}",
                    postingId, pending, captured, failed, poolSize);
            return;
        }
        if (gate.getCapturesSettledAt() == null) {
            gate.setCapturesSettledAt(now);
            log.info("Every coding capture settled: postingId={} pool={} captured={} failed={}",
                    postingId, poolSize, captured, failed);
        }

        if (needsReview > 0) {
            boolean firstTime = gate.getReviewRequestedAt() == null;
            gate.setState(ScoringGateState.AWAITING_TNP_REVIEW);
            if (firstTime) {
                gate.setReviewRequestedAt(now);
            }
            gateRepository.save(gate);
            if (firstTime) {
                // Asked once. The gate stays shut until the TnP acts; the engine
                // is not waiting -- it has simply not been told to start.
                List<Object[]> breakdown = captureRepository.reviewBreakdown(postingId);
                notificationService.queueCodingDataReviewRequired(
                        postingId, needsReview, platformsIn(breakdown), describeReview(breakdown));
                log.info("Scoring held for TnP review: postingId={} students to review={}",
                        postingId, needsReview);
            }
            return;
        }

        // Seat arithmetic, settled here so the engine does not have to know about
        // reviews: a direct pick uses one of the drive's seats, so the engine
        // fills the target minus the picks. Neither kind of reviewed student is
        // scored.
        int direct = (int) reviewRepository.countInPool(postingId, CodingReviewDecision.SHORTLIST_DIRECTLY);
        int removed = (int) reviewRepository.countInPool(postingId, CodingReviewDecision.REMOVE);
        Integer target = posting.getMaxApplications();
        Integer engineSeats = target == null ? null : Math.max(0, target - direct);
        if (target != null && direct > target) {
            log.warn("postingId={} has {} direct picks against a target of {}; the engine will shortlist nobody",
                    postingId, direct, target);
        }

        String eventId = outboxWriter.write(
                DriveTopics.DRIVE_READY_FOR_SCORING,
                "DriveReadyForScoring",
                "JobPosting",
                postingId,
                String.valueOf(postingId),
                null,
                new DriveReadyForScoringEvent(postingId, now, poolSize, captured, failed,
                        posting.getShortlistingMode().name(), target, direct, removed, engineSeats));

        gate.setState(ScoringGateState.READY);
        gate.setReadyAt(now);
        gate.setReadyEventId(eventId);
        gateRepository.save(gate);
        log.info("Drive READY for scoring: postingId={} pool={} captured={} failed={} directPicks={} removed={} engineSeats={} eventId={}",
                postingId, poolSize, captured, failed, direct, removed, engineSeats, eventId);
    }

    /**
     * Move these captures to the TnP and tell each student once, listing the
     * platforms involved. Caller holds the gate lock; the ids come from a locked
     * read.
     */
    private void escalate(List<Long> captureIds, CaptureFlagReason reason, JobPosting posting, Instant now) {
        if (captureIds.isEmpty()) {
            return;
        }
        List<ApplicationCodingCapture> captures = captureRepository.findByIdIn(captureIds);
        Map<Long, Set<CodingPlatform>> platformsByStudent = new TreeMap<>();
        for (ApplicationCodingCapture c : captures) {
            c.escalate(reason, now);
            platformsByStudent.computeIfAbsent(c.getStudentUserId(), k -> EnumSet.noneOf(CodingPlatform.class))
                    .add(c.getPlatform());
        }
        captureRepository.saveAllAndFlush(captures);
        platformsByStudent.forEach((studentUserId, platforms) ->
                notificationService.queueCaptureFlagged(studentUserId, platforms, reason, List.of(posting.getId())));
        log.info("Escalated {} capture(s) to TnP review: postingId={} reason={} students={}",
                captures.size(), posting.getId(), reason, platformsByStudent.size());
    }

    /**
     * Tell the student what one scrape result means for them: one notice per kind,
     * listing every drive it affects. All captures here belong to one job, so to
     * one student and one platform.
     */
    private void notifyStudent(Map<CaptureOutcomePolicy.Notice, List<ApplicationCodingCapture>> byNotice,
                               Map<Long, JobPosting> postings) {
        for (Map.Entry<CaptureOutcomePolicy.Notice, List<ApplicationCodingCapture>> e : byNotice.entrySet()) {
            List<ApplicationCodingCapture> group = e.getValue();
            ApplicationCodingCapture first = group.get(0);
            switch (e.getKey()) {
                case NONE -> { }
                case CHECK_NEEDED -> notificationService.queueHandleCheckNeeded(first.getStudentUserId(),
                        first.getPlatform(), first.getHandle(), first.getOutcome(), drivesOf(group, postings));
                case STILL_FAILING -> notificationService.queueHandleStillFailing(first.getStudentUserId(),
                        first.getPlatform(), first.getHandle(), drivesOf(group, postings),
                        capturedOtherPlatforms(group));
                case FLAGGED -> {
                    // SERVER_ERROR and AFTER_DEADLINE can both come out of one
                    // result (two drives, one past its deadline): one notice each.
                    Map<CaptureFlagReason, List<ApplicationCodingCapture>> byReason =
                            new java.util.EnumMap<>(CaptureFlagReason.class);
                    group.forEach(c -> byReason.computeIfAbsent(c.getFlagReason(), k -> new ArrayList<>()).add(c));
                    byReason.forEach((reason, cs) -> notificationService.queueCaptureFlagged(
                            first.getStudentUserId(), List.of(first.getPlatform()), reason, drivesOf(cs, postings)));
                }
            }
        }
    }

    private static List<Long> drivesOf(List<ApplicationCodingCapture> captures, Map<Long, JobPosting> postings) {
        // IDs only: the notification service re-reads each drive itself.
        Map<Long, Long> out = new LinkedHashMap<>();
        captures.forEach(c -> {
            JobPosting p = postings.get(c.getPostingId());
            if (p != null) {
                out.putIfAbsent(p.getId(), p.getId());
            }
        });
        return new ArrayList<>(out.values());
    }

    /** Platforms already captured on these applications, other than the one that failed. */
    private Set<CodingPlatform> capturedOtherPlatforms(List<ApplicationCodingCapture> failed) {
        CodingPlatform failedPlatform = failed.get(0).getPlatform();
        Set<CodingPlatform> out = EnumSet.noneOf(CodingPlatform.class);
        captureRepository.findLiveByApplicationIdIn(
                        failed.stream().map(ApplicationCodingCapture::getApplicationId).toList())
                .stream()
                .filter(c -> c.getState() == CaptureState.CAPTURED && c.getPlatform() != failedPlatform)
                .forEach(c -> out.add(c.getPlatform()));
        return out;
    }

    /** "CodeChef, LeetCode" -- the platforms the TnP is being asked about. */
    private static String platformsIn(List<Object[]> breakdown) {
        return breakdown.stream()
                .map(r -> CodingPlatform.fromLabel((String) r[0]).label())
                .distinct()
                .collect(Collectors.joining(", "));
    }

    /** "CodeChef: 3 (PLATFORM_ERROR); LeetCode: 1 (HANDLE_NOT_FOUND)" */
    private static String describeReview(List<Object[]> breakdown) {
        List<String> parts = new ArrayList<>();
        for (Object[] r : breakdown) {
            CodingPlatform p = CodingPlatform.fromLabel((String) r[0]);
            parts.add(p.label() + ": " + ((Number) r[2]).intValue() + " (" + r[1] + ")");
        }
        return String.join("; ", parts);
    }

    /** Gates the reconciler should look at. */
    @Transactional(readOnly = true)
    public List<Long> openGatePostingIds() {
        return gateRepository.findPostingIdsByStateIn(
                List.of(ScoringGateState.COLLECTING, ScoringGateState.AWAITING_TNP_REVIEW));
    }
}
