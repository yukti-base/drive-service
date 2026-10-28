package org.yuktisetu.driveservice.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The TnP's coding-review screen for one drive.
 *
 * Everything the reviewer needs to decide about a student whose coding data
 * could not be captured, in one response: what went wrong, what we did capture,
 * earlier data we hold, every coding profile they listed, their academics, and
 * the rest of their record.
 */
public record CodingReviewResponse(
        Long postingId,
        String companyName,
        String postingTitle,
        /** COLLECTING, AWAITING_TNP_REVIEW, READY, CANCELLED -- or NOT_SEALED before the deadline. */
        String gateState,
        Instant readyAt,
        int poolSize,
        int pendingCaptures,
        /** Seats: the drive's shortlist target, the TnP's direct picks, and what the engine will fill. */
        Integer shortlistTarget,
        int directlyShortlisted,
        int removed,
        Integer engineSeats,
        int awaitingDecision,
        /** Flagged students in colleges outside the reviewer's scope, which someone else must decide. */
        int outsideYourScope,
        List<Item> items
) {

    public record Item(
            Long applicationId,
            Long studentUserId,
            String studentName,
            String email,
            Long collegeId,
            Integer batchYear,
            Double cgpa,
            Integer activeBacklogs,
            List<CaptureView> captures,
            List<SnapshotView> earlierData,
            List<CodingProfileView> codingProfiles,
            List<ProjectView> projects,
            List<ExperienceView> experience,
            List<CertificationView> certifications,
            DecisionView decision
    ) {}

    /** One platform's capture for this application: what happened when they applied. */
    public record CaptureView(
            String platform,
            String handle,
            String state,
            String outcome,
            String detail,
            String reviewState,
            Instant requestedAt,
            Instant settledAt,
            Long snapshotId,
            /** SERVER_ERROR, NO_RECHECK, AFTER_DEADLINE, NOTHING_LEFT; null when not flagged. */
            String flagReason,
            /** 1 when the student re-checked this handle before the deadline. */
            int recheckCount,
            /** The handle before the student's re-check, when they corrected it. */
            String previousHandle
    ) {}

    /** The newest successful read we hold for a platform, at any time. */
    public record SnapshotView(
            String platform,
            String handle,
            Instant scrapedAt,
            Map<String, Object> metrics
    ) {}

    public record CodingProfileView(String platform, String username, String profileLink, Double selfReportedRating) {}

    public record ProjectView(String title, String technologies, String link, String description) {}

    public record ExperienceView(String company, String role, String duration) {}

    public record CertificationView(String name, String issuer, String verificationStatus, String credentialUrl) {}

    public record DecisionView(String decision, String reason, Long decidedBy, String decidedByName, Instant decidedAt) {}
}
