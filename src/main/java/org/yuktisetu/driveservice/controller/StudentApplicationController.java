package org.yuktisetu.driveservice.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yuktisetu.core.response.YuktiSetuResponse;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.driveservice.dto.ApplicationResponse;
import org.yuktisetu.driveservice.service.ApplicationService;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/applications")
@RequiredArgsConstructor
public class StudentApplicationController {

    private final ApplicationService applicationService;

    /** Always the caller's own applications; there is no id parameter to abuse. */
    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_STUDENT')")
    public ResponseEntity<YuktiSetuResponse<List<ApplicationResponse>>> myApplications(
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(YuktiSetuResponse.success(
                applicationService.myApplications(actor.userId())));
    }
}
