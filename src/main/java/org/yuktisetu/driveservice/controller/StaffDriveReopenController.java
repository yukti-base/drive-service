package org.yuktisetu.driveservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yuktisetu.core.response.YuktiSetuResponse;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.driveservice.dto.ReopenDriveRequest;
import org.yuktisetu.driveservice.service.DriveReopenService;

/**
 * Extend a sealed drive's deadline and reopen applications. TNP_COORDINATOR and
 * above (decided 2026-10-03), over every college the drive targets -- it changes
 * the whole drive, like publishing its shortlist.
 */
@RestController
@RequestMapping("/staff/postings/{postingId}/reopen")
@RequiredArgsConstructor
public class StaffDriveReopenController {

    private static final String REVIEWERS =
            "hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN','ROLE_TNP_COLLEGE_ADMIN','ROLE_TNP_COORDINATOR')";

    private final DriveReopenService reopenService;

    @PostMapping
    @PreAuthorize(REVIEWERS)
    public ResponseEntity<YuktiSetuResponse<ReopenDriveRequest.Result>> reopen(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long postingId,
            @RequestBody @Valid ReopenDriveRequest request) {
        return ResponseEntity.ok(YuktiSetuResponse.success(
                reopenService.reopen(actor, postingId, request), "Drive reopened"));
    }
}
