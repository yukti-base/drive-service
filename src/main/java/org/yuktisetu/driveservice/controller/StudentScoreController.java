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
import org.yuktisetu.driveservice.dto.ScoreResponses.StudentScoresResponse;
import org.yuktisetu.driveservice.service.StudentScoreService;

/**
 * The student's own composite scores. Always the caller's; no id parameter.
 * Empty unless the platform's score-visibility switch is on -- see
 * StudentScoreService for every condition.
 */
@RestController
@RequestMapping("/my-scores")
@RequiredArgsConstructor
public class StudentScoreController {

    private final StudentScoreService scoreService;

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_STUDENT')")
    public ResponseEntity<YuktiSetuResponse<StudentScoresResponse>> myScores(
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(YuktiSetuResponse.success(scoreService.myScores(actor.userId())));
    }
}
