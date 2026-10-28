package org.yuktisetu.driveservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Extend a sealed drive's deadline and reopen applications. The reason is
 * recorded on every affected application's history, so it is required.
 */
public record ReopenDriveRequest(
        @NotNull Instant applicationDeadline,
        @NotBlank @Size(max = 500) String reason
) {
    /** What the reopen did, for the confirmation the TnP sees. */
    public record Result(
            Long postingId,
            Instant applicationDeadline,
            int reopenCount,
            int applicationsReopened,
            int previouslyShortlisted,
            Long supersededRunId,
            int eligibleNotified
    ) {}
}
