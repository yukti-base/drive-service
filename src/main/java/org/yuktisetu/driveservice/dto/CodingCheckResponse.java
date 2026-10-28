package org.yuktisetu.driveservice.dto;

import java.time.Instant;
import java.util.List;

/**
 * The student's coding-check banner: every platform where something about their
 * coding data needs their attention or is worth telling them, on their live
 * applications. Empty when all is well, which is the usual case.
 */
public record CodingCheckResponse(List<Item> items) {

    /**
     * One platform in one situation, across the drives it affects.
     *
     * status:
     *   NEEDS_RECHECK     the handle could not be read; canRecheck says whether they still can
     *   CHECKING          their re-check is being read now
     *   CONFIRMED         their re-check worked
     *   SCORED_ON_OTHERS  the re-check failed on the handle too; scored on their other platforms
     *   WITH_TNP          the TnP will decide; flagReason says why
     */
    public record Item(
            String platform,
            String platformLabel,
            String status,
            String handle,
            String previousHandle,
            String outcome,
            String flagReason,
            boolean canRecheck,
            Instant recheckBy,
            List<Drive> drives) {}

    public record Drive(Long postingId, String title, String companyName, Instant deadline) {}
}
