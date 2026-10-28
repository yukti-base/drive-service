package org.yuktisetu.driveservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.yuktisetu.drive.model.CodingReviewDecision;

import java.util.List;

/**
 * One decision for one or more flagged applications.
 *
 * "Decide all" in the portal is this same request carrying every pending id the
 * reviewer can see, with one shared reason. The reason is mandatory: it is the
 * recorded answer when a student asks why they were (not) shortlisted.
 */
public record CodingReviewDecisionRequest(
        @NotEmpty @Size(max = 2000) List<@NotNull Long> applicationIds,
        @NotNull CodingReviewDecision decision,
        @NotBlank @Size(max = 1000) String reason
) {}
