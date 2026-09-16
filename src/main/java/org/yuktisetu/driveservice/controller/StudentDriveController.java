package org.yuktisetu.driveservice.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.yuktisetu.core.response.YuktiSetuResponse;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.driveservice.dto.ApplicationResponse;
import org.yuktisetu.driveservice.dto.MyEligibilityResponse;
import org.yuktisetu.driveservice.dto.StudentDriveDetailResponse;
import org.yuktisetu.driveservice.dto.StudentDriveSummaryResponse;
import org.yuktisetu.driveservice.service.ApplicationService;
import org.yuktisetu.driveservice.service.StudentDriveService;

import java.util.List;

/**
 * The student's side of a drive: what they can see, and Easy Apply.
 *
 * Every endpoint acts on the authenticated student and takes no student id from
 * the caller -- there is no parameter through which one student could ask about
 * another's applications.
 */
@Slf4j
@RestController
@RequestMapping("/postings")
@RequiredArgsConstructor
public class StudentDriveController {

    private final StudentDriveService studentDriveService;
    private final ApplicationService applicationService;

    /** tab: open (default) | closed | all */
    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_STUDENT')")
    public ResponseEntity<YuktiSetuResponse<List<StudentDriveSummaryResponse>>> list(
            @AuthenticationPrincipal UserPrincipal actor,
            @RequestParam(required = false, defaultValue = "open") String tab) {
        return ResponseEntity.ok(YuktiSetuResponse.success(
                studentDriveService.listForStudent(actor.userId(), tab)));
    }

    /** 404 rather than 403 when ineligible -- see DriveExceptions.DriveNotVisibleException. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_STUDENT')")
    public ResponseEntity<YuktiSetuResponse<StudentDriveDetailResponse>> get(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id) {
        return ResponseEntity.ok(YuktiSetuResponse.success(
                studentDriveService.getForStudent(id, actor.userId())));
    }

    /** "Why can't I apply?", answered with the same live check apply itself runs. */
    @GetMapping("/{id}/my-eligibility")
    @PreAuthorize("hasAuthority('ROLE_STUDENT')")
    public ResponseEntity<YuktiSetuResponse<MyEligibilityResponse>> myEligibility(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id) {
        return ResponseEntity.ok(YuktiSetuResponse.success(
                studentDriveService.myEligibility(id, actor.userId())));
    }

    @PostMapping("/{id}/apply")
    @PreAuthorize("hasAuthority('ROLE_STUDENT')")
    public ResponseEntity<YuktiSetuResponse<ApplicationResponse>> apply(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(YuktiSetuResponse.success(
                        applicationService.apply(id, actor.userId()), 201, "Application submitted"));
    }

    @DeleteMapping("/{id}/apply")
    @PreAuthorize("hasAuthority('ROLE_STUDENT')")
    public ResponseEntity<YuktiSetuResponse<ApplicationResponse>> withdraw(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id) {
        return ResponseEntity.ok(YuktiSetuResponse.success(
                applicationService.withdraw(id, actor.userId()), "Application withdrawn"));
    }
}
