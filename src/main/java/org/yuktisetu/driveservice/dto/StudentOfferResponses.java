package org.yuktisetu.driveservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** A student's offers and what they may do with each (decided 2026-11-01). */
public final class StudentOfferResponses {

    private StudentOfferResponses() {}

    /** A decline or withdrawal always carries the student's reason. */
    public record ReasonRequest(@NotBlank @Size(min = 5, max = 500) String reason) {}

    public record WithdrawalRequestView(
            Long id,
            String status,
            String reason,
            Instant requestedAt,
            Instant decidedAt,
            String decisionNote) {}

    public record MyOffer(
            Long offerId,
            Long applicationId,
            Long postingId,
            String companyName,
            String postingTitle,
            String offerType,
            String opportunity,
            BigDecimal ctcAmount,
            String ctcCurrency,
            BigDecimal stipendAmount,
            String stipendPeriod,
            String status,
            LocalDate offerDate,
            Instant acceptedAt,
            Instant declinedAt,
            String declineReason,
            Instant withdrawnAt,
            String withdrawalReason,
            Instant revokedAt,
            String revokeReason,
            /** The newest request, pending or decided; null if none was made. */
            WithdrawalRequestView withdrawalRequest,
            boolean canAccept,
            boolean canDecline,
            boolean canRequestWithdrawal) {}
}
