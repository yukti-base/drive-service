package org.yuktisetu.driveservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.drive.db.ApplicationCodingCapture;
import org.yuktisetu.drive.db.JobPosting;
import org.yuktisetu.drive.model.CaptureReviewState;
import org.yuktisetu.drive.repository.ApplicationCodingCaptureRepository;
import org.yuktisetu.drive.repository.JobPostingRepository;
import org.yuktisetu.driveservice.dto.CodingCheckResponse;
import org.yuktisetu.driveservice.exception.DriveExceptions;
import org.yuktisetu.profile.model.CodingPlatform;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The student's side of a coding capture that could not be read: what they see,
 * and their one re-check per platform. Decided 2026-10-18.
 *
 * FIX ONCE, APPLIES EVERYWHERE. A re-check updates the handle on the student's
 * profile and re-reads it for every open application waiting on that platform,
 * with one scrape shared by all of them. A typo that failed on three drives is
 * one correction, and the next drive they apply to reads the corrected handle.
 *
 * NOTHING WAITS HERE EITHER. The re-check writes a scrape job and returns; the
 * result arrives through the same event path as an apply-time capture, and is
 * settled by CaptureOutcomePolicy.
 *
 * LOCK ORDER: posting rows shared, in id order -- the same lock an apply takes,
 * so a re-check and the deadline seal (which holds the posting exclusively)
 * cannot interleave -- then the captures, then the profile row, then the job.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodingRecheckService {

    private final ApplicationCodingCaptureRepository captureRepository;
    private final JobPostingRepository postingRepository;
    private final CodingCaptureService captureService;
    private final JdbcTemplate jdbc;

    /** What the student's banner shows. Usually empty. */
    @Transactional(readOnly = true)
    public CodingCheckResponse myChecks(Long studentUserId) {
        List<ApplicationCodingCapture> live = captureRepository.findLiveForStudent(studentUserId);

        // Group by platform and situation; one row per pair, across drives.
        Map<String, List<ApplicationCodingCapture>> groups = new LinkedHashMap<>();
        for (ApplicationCodingCapture c : live) {
            String status = statusOf(c);
            if (status == null) {
                continue;
            }
            String flag = c.getFlagReason() == null ? "" : c.getFlagReason().name();
            groups.computeIfAbsent(c.getPlatform() + "|" + status + "|" + flag, k -> new ArrayList<>()).add(c);
        }
        if (groups.isEmpty()) {
            return new CodingCheckResponse(List.of());
        }

        Map<Long, JobPosting> postings = new HashMap<>();
        postingRepository.findAllById(live.stream().map(ApplicationCodingCapture::getPostingId).distinct().toList())
                .forEach(p -> postings.put(p.getId(), p));

        Instant now = Instant.now();
        List<CodingCheckResponse.Item> items = new ArrayList<>();
        for (List<ApplicationCodingCapture> group : groups.values()) {
            ApplicationCodingCapture first = group.get(0);
            String status = statusOf(first);

            List<JobPosting> drives = group.stream().map(c -> postings.get(c.getPostingId()))
                    .filter(Objects::nonNull).distinct().toList();
            List<JobPosting> open = drives.stream().filter(p -> CaptureOutcomePolicy.recheckOpen(p, now)).toList();
            Instant recheckBy = open.stream().map(JobPosting::getApplicationDeadline)
                    .min(Comparator.naturalOrder()).orElse(null);

            items.add(new CodingCheckResponse.Item(
                    first.getPlatform().name(),
                    first.getPlatform().label(),
                    status,
                    first.getHandle(),
                    first.getPreviousHandle(),
                    first.getOutcome(),
                    first.getFlagReason() == null ? null : first.getFlagReason().name(),
                    "NEEDS_RECHECK".equals(status) && !open.isEmpty(),
                    recheckBy,
                    drives.stream().map(p -> new CodingCheckResponse.Drive(
                            p.getId(), p.getTitle(), p.getCompany().getName(), p.getApplicationDeadline())).toList()));
        }
        // What they can act on first.
        items.sort(Comparator.comparing((CodingCheckResponse.Item i) -> !i.canRecheck())
                .thenComparing(CodingCheckResponse.Item::platformLabel));
        return new CodingCheckResponse(items);
    }

    /** The banner status for one capture, or null when there is nothing to tell the student. */
    private static String statusOf(ApplicationCodingCapture c) {
        return switch (c.getState()) {
            case AWAITING_STUDENT -> "NEEDS_RECHECK";
            case PENDING -> c.getRecheckCount() > 0 ? "CHECKING" : null;
            case CAPTURED -> c.getRecheckCount() > 0 ? "CONFIRMED" : null;
            case FAILED -> c.getReviewState() == CaptureReviewState.NOT_REQUIRED ? "SCORED_ON_OTHERS" : "WITH_TNP";
            case SUPERSEDED -> null;
        };
    }

    /**
     * Re-check one platform: correct the handle if needed, and read it again for
     * every open application waiting on it.
     */
    @Transactional
    public CodingCheckResponse recheck(Long studentUserId, CodingPlatform platform, String rawHandle) {
        String handle = rawHandle.trim();
        if (!captureService.captures(platform)) {
            throw new DriveExceptions.InvalidRequestException(platform.label() + " profiles are not read automatically.");
        }

        List<ApplicationCodingCapture> waiting = captureRepository.findAwaitingForStudent(studentUserId, platform.name());
        if (waiting.isEmpty()) {
            throw new DriveExceptions.RecheckUnavailableException("There is nothing to re-check on " + platform.label()
                    + ": your re-check has already been used, or the drive's deadline has passed.");
        }

        // Posting rows shared, in id order: the apply lock. The seal holds the row
        // exclusively, so after this a drive is either still open and will stay
        // open until we commit, or already sealed -- never sealed halfway through.
        Instant now = Instant.now();
        Set<Long> openPostingIds = new TreeSet<>();
        for (Long postingId : new TreeSet<>(waiting.stream().map(ApplicationCodingCapture::getPostingId).toList())) {
            postingRepository.findForApply(postingId)
                    .filter(p -> CaptureOutcomePolicy.recheckOpen(p, now))
                    .ifPresent(p -> openPostingIds.add(p.getId()));
        }
        if (openPostingIds.isEmpty()) {
            throw new DriveExceptions.RecheckUnavailableException("The deadline has passed for every drive waiting on "
                    + platform.label() + ", so it can no longer be re-checked. The TnP Cell will review it.");
        }

        List<ApplicationCodingCapture> captures =
                captureRepository.lockAwaitingForStudent(studentUserId, platform.name(), openPostingIds);
        if (captures.isEmpty()) {
            // A second tab got here first.
            throw new DriveExceptions.RecheckUnavailableException(
                    "This " + platform.label() + " re-check has already been started.");
        }

        String previous = updateProfileHandle(studentUserId, platform, handle);

        Long firstPostingId = openPostingIds.iterator().next();
        Long jobId = captureService.enqueueOrJoin(studentUserId, firstPostingId, Map.of(platform, handle),
                CodingCaptureService.REASON_RECHECK).get(platform);
        for (ApplicationCodingCapture c : captures) {
            c.startRecheck(handle, jobId, now);
        }
        captureRepository.saveAllAndFlush(captures);

        // The audit line for the TnP sheet: who changed which handle, from what.
        log.info("Coding re-check: studentUserId={} platform={} handle '{}' -> '{}' jobId={} applications={}",
                studentUserId, platform, previous, handle, jobId,
                captures.stream().map(ApplicationCodingCapture::getApplicationId).collect(Collectors.toList()));

        return myChecks(studentUserId);
    }

    /**
     * Write the handle onto the student's profile, so the correction holds for
     * every later application too. Returns the handle it replaced.
     *
     * The profile's first entry for the platform is the one capture reads (see
     * CodingCaptureService.handlesFor), so it is the one corrected here. A student
     * who has since removed the platform from their profile has nothing to correct.
     */
    private String updateProfileHandle(Long studentUserId, CodingPlatform platform, String handle) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT cp.id, cp.platform, cp.username
                  FROM student_coding_profiles cp
                  JOIN student_profiles sp ON sp.id = cp.student_profile_id
                 WHERE sp.user_id = ?
                 ORDER BY cp.id
                   FOR UPDATE OF cp
                """, studentUserId);
        for (Map<String, Object> r : rows) {
            if (CodingPlatform.fromLabel((String) r.get("platform")) == platform) {
                String previous = (String) r.get("username");
                if (!handle.equals(previous)) {
                    jdbc.update("UPDATE student_coding_profiles SET username = ? WHERE id = ?",
                            handle, ((Number) r.get("id")).longValue());
                }
                return previous;
            }
        }
        throw new DriveExceptions.RecheckUnavailableException(platform.label()
                + " is no longer on your profile. Add it on your Profile page, then re-check.");
    }
}
