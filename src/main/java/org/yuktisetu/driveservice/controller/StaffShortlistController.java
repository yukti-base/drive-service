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
import org.yuktisetu.driveservice.dto.ScoreResponses.PublishShortlistRequest;
import org.yuktisetu.driveservice.dto.ScoreResponses.StaffDriveScores;
import org.yuktisetu.driveservice.service.ShortlistService;

/**
 * The TnP's "Scores & shortlist" screen for one drive: the ranked pool, approval
 * of a run held over unmeasurable data, and publishing the shortlist.
 *
 * Same role band as the coding review. Reading needs any targeted college in
 * scope; approving and publishing decide for the whole drive, so they need
 * every targeted college (ShortlistService).
 */
@RestController
@RequestMapping("/staff/postings/{postingId}/scores")
@RequiredArgsConstructor
public class StaffShortlistController {

    private static final String REVIEWERS =
            "hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN','ROLE_TNP_COLLEGE_ADMIN','ROLE_TNP_COORDINATOR')";

    private final ShortlistService shortlistService;

    @GetMapping
    @PreAuthorize(REVIEWERS)
    public ResponseEntity<YuktiSetuResponse<StaffDriveScores>> scores(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long postingId) {
        return ResponseEntity.ok(YuktiSetuResponse.success(shortlistService.scores(actor, postingId)));
    }

    /** Proceed with a run held because some students had a factor that could not be measured. */
    @PostMapping("/approve-held")
    @PreAuthorize(REVIEWERS)
    public ResponseEntity<YuktiSetuResponse<StaffDriveScores>> approveHeld(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long postingId) {
        return ResponseEntity.ok(YuktiSetuResponse.success(
                shortlistService.approveHeld(actor, postingId), "Scoring approved"));
    }

    @PostMapping("/publish")
    @PreAuthorize(REVIEWERS)
    public ResponseEntity<YuktiSetuResponse<StaffDriveScores>> publish(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long postingId,
            @RequestBody @Valid PublishShortlistRequest request) {
        return ResponseEntity.ok(YuktiSetuResponse.success(
                shortlistService.publish(actor, postingId, request.applicationIds()), "Shortlist published"));
    }
}
