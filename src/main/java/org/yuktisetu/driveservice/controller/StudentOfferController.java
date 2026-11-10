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
import org.yuktisetu.driveservice.dto.StudentOfferResponses.MyOffer;
import org.yuktisetu.driveservice.dto.StudentOfferResponses.ReasonRequest;
import org.yuktisetu.driveservice.service.StudentOfferService;

import java.util.List;

/**
 * The student's offers: accept, decline (with a reason), or ask to withdraw an
 * accepted one (with a reason, decided by the TnP in admin-service).
 */
@RestController
@RequestMapping("/offers")
@RequiredArgsConstructor
public class StudentOfferController {

    private final StudentOfferService offerService;

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_STUDENT')")
    public ResponseEntity<YuktiSetuResponse<List<MyOffer>>> mine(@AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(YuktiSetuResponse.success(offerService.myOffers(actor.userId())));
    }

    @PostMapping("/{offerId}/accept")
    @PreAuthorize("hasAuthority('ROLE_STUDENT')")
    public ResponseEntity<YuktiSetuResponse<MyOffer>> accept(@AuthenticationPrincipal UserPrincipal actor,
                                                            @PathVariable Long offerId) {
        return ResponseEntity.ok(YuktiSetuResponse.success(
                offerService.accept(actor.userId(), offerId), "Offer accepted"));
    }

    @PostMapping("/{offerId}/decline")
    @PreAuthorize("hasAuthority('ROLE_STUDENT')")
    public ResponseEntity<YuktiSetuResponse<MyOffer>> decline(@AuthenticationPrincipal UserPrincipal actor,
                                                             @PathVariable Long offerId,
                                                             @Valid @RequestBody ReasonRequest request) {
        return ResponseEntity.ok(YuktiSetuResponse.success(
                offerService.decline(actor.userId(), offerId, request.reason()), "Offer declined"));
    }

    @PostMapping("/{offerId}/withdrawal-request")
    @PreAuthorize("hasAuthority('ROLE_STUDENT')")
    public ResponseEntity<YuktiSetuResponse<MyOffer>> requestWithdrawal(@AuthenticationPrincipal UserPrincipal actor,
                                                                       @PathVariable Long offerId,
                                                                       @Valid @RequestBody ReasonRequest request) {
        return ResponseEntity.ok(YuktiSetuResponse.success(
                offerService.requestWithdrawal(actor.userId(), offerId, request.reason()),
                "Withdrawal requested; the TnP will review it"));
    }
}
