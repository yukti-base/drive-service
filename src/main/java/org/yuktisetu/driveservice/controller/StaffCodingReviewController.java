package org.yuktisetu.driveservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yuktisetu.core.response.YuktiSetuResponse;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.driveservice.dto.CodingReviewDecisionRequest;
import org.yuktisetu.driveservice.dto.CodingReviewResponse;
import org.yuktisetu.driveservice.service.CodingReviewService;

/**
 * The TnP's coding review for a sealed drive -- the screen the "shortlisting
 * held" email links to.
 *
 * Staff-only. The @PreAuthorize band is the coarse gate; CodingReviewPolicy then
 * narrows every read and every decision to the students of the reviewer's own
 * colleges.
 */
@RestController
@RequestMapping("/staff/postings/{postingId}/coding-review")
@RequiredArgsConstructor
public class StaffCodingReviewController {

    private static final String REVIEWERS =
            "hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN','ROLE_TNP_COLLEGE_ADMIN','ROLE_TNP_COORDINATOR')";

    private final CodingReviewService reviewService;

    @GetMapping
    @PreAuthorize(REVIEWERS)
    public ResponseEntity<YuktiSetuResponse<CodingReviewResponse>> review(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long postingId) {
        return ResponseEntity.ok(YuktiSetuResponse.success(reviewService.review(actor, postingId)));
    }

    /**
     * Shortlist directly, or remove -- for one application or many. Returns the
     * refreshed review, including whether the drive has now become ready.
     */
    @PostMapping("/decisions")
    @PreAuthorize(REVIEWERS)
    public ResponseEntity<YuktiSetuResponse<CodingReviewResponse>> decide(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long postingId,
            @RequestBody @Valid CodingReviewDecisionRequest request) {
        return ResponseEntity.ok(YuktiSetuResponse.success(
                reviewService.decide(actor, postingId, request), "Decision recorded"));
    }
}
