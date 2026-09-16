package org.yuktisetu.driveservice.dto;

import java.util.List;

/**
 * Why a student can or cannot apply, in their own words-to-be.
 *
 * Returned as stable codes rather than prose so the portal can render them in
 * whatever wording the TnP settles on, and so the same answer comes back months
 * later. Transparency here is deliberate: a student who can see the reason does
 * not have to ask the TnP for it.
 */
public record MyEligibilityResponse(
        Long postingId,
        boolean eligible,
        List<String> failureCodes
) {}
