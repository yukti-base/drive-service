package org.yuktisetu.driveservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yuktisetu.core.response.YuktiSetuResponse;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.driveservice.dto.CodingCheckResponse;
import org.yuktisetu.driveservice.dto.CodingRecheckRequest;
import org.yuktisetu.driveservice.service.CodingRecheckService;

/**
 * The student's coding-handle checks. Always the caller's own; there is no id
 * parameter through which one student could re-check another's handle.
 */
@RestController
@RequestMapping("/coding-checks")
@RequiredArgsConstructor
public class StudentCodingCheckController {

    private final CodingRecheckService recheckService;

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_STUDENT')")
    public ResponseEntity<YuktiSetuResponse<CodingCheckResponse>> myChecks(
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(YuktiSetuResponse.success(recheckService.myChecks(actor.userId())));
    }

    /** Re-check one platform, with the handle unchanged or corrected. Returns the refreshed checks. */
    @PostMapping("/recheck")
    @PreAuthorize("hasAuthority('ROLE_STUDENT')")
    public ResponseEntity<YuktiSetuResponse<CodingCheckResponse>> recheck(
            @AuthenticationPrincipal UserPrincipal actor,
            @Valid @RequestBody CodingRecheckRequest request) {
        return ResponseEntity.ok(YuktiSetuResponse.success(
                recheckService.recheck(actor.userId(), request.platform(), request.handle())));
    }
}
