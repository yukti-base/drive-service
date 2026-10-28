package org.yuktisetu.driveservice.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Request and response shapes for composite scores, staff and student. */
public final class ScoreResponses {

    private ScoreResponses() {}

    // ------------------------------------------------------------------ staff

    /**
     * The TnP's "Scores & shortlist" screen for one drive.
     *
     * runStatus is NOT_SCORED until the engine has run (the drive is still
     * collecting, or held in the coding review), then SCORED, HELD_FOR_REVIEW or
     * DECLINED. On DECLINED the items are the pool with no scores, for a manual
     * pick.
     */
    public record StaffDriveScores(
            Long postingId,
            String companyName,
            String postingTitle,
            String gateState,
            String runStatus,
            Instant scoredAt,
            String declinedReason,
            Map<String, Object> config,
            Map<String, Object> poolExclusions,
            Map<String, Object> codingDropped,
            List<HeldStudent> held,
            Integer shortlistTarget,
            int directPicks,
            Integer engineSeats,
            Instant shortlistPublishedAt,
            boolean canPublish,
            List<StaffScoreItem> items) {}

    public record HeldStudent(Long studentUserId, String name, String email, Map<String, Object> reasons) {}

    public record StaffScoreItem(
            Long applicationId,
            Long studentUserId,
            String name,
            String email,
            String applicationStatus,
            /** SHORTLIST_DIRECTLY or REMOVE from the coding review; null when not reviewed. */
            String reviewDecision,
            Integer rank,
            boolean tied,
            BigDecimal composite,
            Boolean meetsThreshold,
            Boolean shortlistEligible,
            List<String> floorsFailed,
            Map<String, Object> breakdown) {}

    public record PublishShortlistRequest(
            /** Applications the TnP selected. Direct picks from the coding review are added automatically. */
            @NotNull List<Long> applicationIds) {}

    // ---------------------------------------------------------------- student

    /**
     * One drive's score as a student may see it. Deliberately has no rank: a
     * student is shown how their number was built, never where it placed them.
     */
    public record StudentDriveScore(
            Long postingId,
            String companyName,
            String postingTitle,
            String applicationStatus,
            Instant shortlistPublishedAt,
            BigDecimal composite,
            BigDecimal threshold,
            boolean meetsThreshold,
            List<String> floorsFailed,
            boolean weightsRenormalised,
            List<Map<String, Object>> factors,
            Map<String, Object> coding,
            Map<String, Object> weights,
            int poolSize) {}

    public record StudentScoresResponse(boolean visible, List<StudentDriveScore> items) {}
}
