package org.yuktisetu.driveservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.core.exception.ApiException;
import org.yuktisetu.drive.db.JobApplication;
import org.yuktisetu.drive.db.OfferWithdrawalRequest;
import org.yuktisetu.drive.db.PlacementOffer;
import org.yuktisetu.drive.model.ActorKind;
import org.yuktisetu.drive.model.ApplicationStatus;
import org.yuktisetu.drive.model.ApplicationStatusTransitions;
import org.yuktisetu.drive.model.OfferStatus;
import org.yuktisetu.drive.model.OfferStatusTransitions;
import org.yuktisetu.drive.model.OfferType;
import org.yuktisetu.drive.model.WithdrawalRequestStatus;
import org.yuktisetu.drive.repository.JobApplicationRepository;
import org.yuktisetu.drive.repository.OfferWithdrawalRequestRepository;
import org.yuktisetu.drive.repository.PlacementOfferRepository;
import org.yuktisetu.driveservice.dto.StudentOfferResponses.MyOffer;
import org.yuktisetu.driveservice.dto.StudentOfferResponses.WithdrawalRequestView;
import org.yuktisetu.driveservice.exception.DriveExceptions;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * A student's answer to an offer (decided 2026-11-01).
 *
 *  - ACCEPT and DECLINE while the offer is OFFERED. Declining needs a reason.
 *  - After accepting, the student cannot simply decline: they ask to WITHDRAW,
 *    with a reason, and the TnP approves or refuses (admin-service). The offer
 *    stays ACCEPTED -- and the student counts as placed -- until it is approved.
 *  - The company never acts here. If it withdraws an offer it goes to the TnP,
 *    and an IT admin records it (admin-service).
 *
 * Every action locks the offer row first, then re-reads its state, so a student
 * double-clicking, or answering while an IT admin revokes, gets one outcome and a
 * clear conflict for the other. The application moves with the offer in the same
 * transaction, with a history row, and the internship lock is recomputed --
 * declining a live internship frees the student for other internship drives.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudentOfferService {

    private final PlacementOfferRepository offerRepository;
    private final OfferWithdrawalRequestRepository withdrawalRepository;
    private final JobApplicationRepository applicationRepository;
    private final ApplicationService applicationService;
    private final DriveNotificationService notificationService;
    private final NamedParameterJdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public List<MyOffer> myOffers(Long studentUserId) {
        try {
            return jdbc.query("""
                    SELECT o.id, o.application_id, o.posting_id, c.name, p.title, o.offer_type,
                           o.ctc_amount, o.ctc_currency, o.stipend_amount, o.stipend_period, o.status, o.offer_date,
                           o.accepted_at, o.declined_at, o.decline_reason, o.withdrawn_at, o.withdrawal_reason,
                           o.revoked_at, o.revoke_reason,
                           w.id, w.status, w.reason, w.requested_at, w.decided_at, w.decision_note
                      FROM placement_offers o
                      JOIN job_postings p ON p.id = o.posting_id
                      JOIN companies c ON c.id = o.company_id
                      LEFT JOIN LATERAL (
                            SELECT * FROM offer_withdrawal_requests r
                             WHERE r.offer_id = o.id ORDER BY r.requested_at DESC LIMIT 1) w ON true
                     WHERE o.student_user_id = :s AND o.is_deleted = false
                     ORDER BY o.offer_date DESC, o.id DESC
                    """, new MapSqlParameterSource("s", studentUserId), (rs, i) -> {
                OfferStatus status = OfferStatus.valueOf(rs.getString(11));
                long reqId = rs.getLong(20);
                WithdrawalRequestView req = rs.wasNull() ? null : new WithdrawalRequestView(
                        reqId, rs.getString(21), rs.getString(22), instant(rs.getTimestamp(23)),
                        instant(rs.getTimestamp(24)), rs.getString(25));
                boolean pending = req != null && WithdrawalRequestStatus.PENDING.name().equals(req.status());
                OfferType type = OfferType.valueOf(rs.getString(6));
                Date offerDate = rs.getDate(12);
                return new MyOffer(
                        rs.getLong(1), (Long) rs.getObject(2, Long.class), rs.getLong(3), rs.getString(4), rs.getString(5),
                        type.name(), opportunity(type),
                        rs.getBigDecimal(7), rs.getString(8), rs.getBigDecimal(9), rs.getString(10),
                        status.name(), offerDate == null ? null : offerDate.toLocalDate(),
                        instant(rs.getTimestamp(13)), instant(rs.getTimestamp(14)), rs.getString(15),
                        instant(rs.getTimestamp(16)), rs.getString(17), instant(rs.getTimestamp(18)), rs.getString(19),
                        req,
                        status == OfferStatus.OFFERED,
                        status == OfferStatus.OFFERED,
                        status == OfferStatus.ACCEPTED && !pending);
            });
        } catch (Exception e) {
            log.error("Could not list offers for studentUserId={}: {}", studentUserId, e.getMessage(), e);
            throw new DriveExceptions.InvalidRequestException("Could not load your offers right now. Please try again.");
        }
    }

    @Transactional
    public MyOffer accept(Long studentUserId, Long offerId) {
        try {
            PlacementOffer offer = lockOwn(studentUserId, offerId);
            requireStatus(offer, OfferStatus.OFFERED, "OFFER_NOT_OPEN",
                    "This offer can no longer be accepted: it is " + label(offer.getStatus()) + ".");
            move(offer, OfferStatus.ACCEPTED);
            offer.setAcceptedAt(Instant.now());
            offer.setUpdatedBy(studentUserId);
            offerRepository.save(offer);
            moveApplication(offer, ApplicationStatus.OFFERED, ApplicationStatus.OFFER_ACCEPTED, studentUserId,
                    ActorKind.STUDENT, "Offer accepted by the student");
            log.info("Offer accepted: offerId={} studentUserId={} postingId={}", offerId, studentUserId,
                    offer.getPosting().getId());
            return one(studentUserId, offerId);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Accepting offerId={} for studentUserId={} failed: {}", offerId, studentUserId, e.getMessage(), e);
            throw new DriveExceptions.InvalidRequestException("Could not accept the offer right now. Please try again.");
        }
    }

    @Transactional
    public MyOffer decline(Long studentUserId, Long offerId, String reason) {
        try {
            String why = requireReason(reason);
            PlacementOffer offer = lockOwn(studentUserId, offerId);
            if (offer.getStatus() == OfferStatus.ACCEPTED) {
                throw new DriveExceptions.OfferStateException("OFFER_ALREADY_ACCEPTED",
                        "You have accepted this offer. To give it up, request a withdrawal; the TnP will review it.");
            }
            requireStatus(offer, OfferStatus.OFFERED, "OFFER_NOT_OPEN",
                    "This offer can no longer be declined: it is " + label(offer.getStatus()) + ".");
            move(offer, OfferStatus.DECLINED);
            offer.setDeclinedAt(Instant.now());
            offer.setDeclineReason(why);
            offer.setUpdatedBy(studentUserId);
            offerRepository.save(offer);
            moveApplication(offer, ApplicationStatus.OFFERED, ApplicationStatus.OFFER_DECLINED, studentUserId,
                    ActorKind.STUDENT, truncate("Offer declined by the student: " + why));
            log.info("Offer declined: offerId={} studentUserId={} reason={}", offerId, studentUserId, why);
            return one(studentUserId, offerId);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Declining offerId={} for studentUserId={} failed: {}", offerId, studentUserId, e.getMessage(), e);
            throw new DriveExceptions.InvalidRequestException("Could not decline the offer right now. Please try again.");
        }
    }

    /** Files the request; the offer does not change until the TnP approves it. */
    @Transactional
    public MyOffer requestWithdrawal(Long studentUserId, Long offerId, String reason) {
        try {
            String why = requireReason(reason);
            PlacementOffer offer = lockOwn(studentUserId, offerId);
            if (offer.getStatus() == OfferStatus.OFFERED) {
                throw new DriveExceptions.OfferStateException("OFFER_NOT_ACCEPTED",
                        "You have not accepted this offer yet. Decline it instead.");
            }
            requireStatus(offer, OfferStatus.ACCEPTED, "OFFER_NOT_ACCEPTED",
                    "Only an accepted offer can be withdrawn; this one is " + label(offer.getStatus()) + ".");
            if (withdrawalRepository.findByOfferIdAndStatus(offerId, WithdrawalRequestStatus.PENDING).isPresent()) {
                throw new DriveExceptions.OfferStateException("WITHDRAWAL_PENDING",
                        "You have already asked to withdraw this offer. The TnP will review it.");
            }
            OfferWithdrawalRequest request = new OfferWithdrawalRequest();
            request.setOfferId(offerId);
            request.setPostingId(offer.getPosting().getId());
            request.setStudentUserId(studentUserId);
            request.setReason(why);
            request.setPendingLock(offerId);
            request.setRequestedAt(Instant.now());
            try {
                withdrawalRepository.saveAndFlush(request);
            } catch (DataIntegrityViolationException dup) {
                // uk_withdrawal_pending_per_offer: a request filed in the same instant.
                throw new DriveExceptions.OfferStateException("WITHDRAWAL_PENDING",
                        "You have already asked to withdraw this offer. The TnP will review it.");
            }
            notificationService.queueOfferWithdrawalRequested(offer.getPosting().getId(), studentUserId, why);
            log.info("Offer withdrawal requested: offerId={} requestId={} studentUserId={} reason={}",
                    offerId, request.getId(), studentUserId, why);
            return one(studentUserId, offerId);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Withdrawal request for offerId={} by studentUserId={} failed: {}", offerId, studentUserId,
                    e.getMessage(), e);
            throw new DriveExceptions.InvalidRequestException("Could not send the request right now. Please try again.");
        }
    }

    // ------------------------------------------------------------------ helpers

    private PlacementOffer lockOwn(Long studentUserId, Long offerId) {
        PlacementOffer offer = offerRepository.lockById(offerId)
                .orElseThrow(() -> new DriveExceptions.OfferStateException("OFFER_NOT_FOUND", "No such offer."));
        // Not "forbidden": an offer id that is not yours looks exactly like one
        // that does not exist, so ids cannot be walked.
        if (!studentUserId.equals(offer.getStudentUserId())) {
            log.warn("studentUserId={} acted on offerId={} owned by userId={}", studentUserId, offerId,
                    offer.getStudentUserId());
            throw new DriveExceptions.OfferStateException("OFFER_NOT_FOUND", "No such offer.");
        }
        return offer;
    }

    private static void requireStatus(PlacementOffer offer, OfferStatus expected, String code, String message) {
        if (offer.getStatus() != expected) {
            throw new DriveExceptions.OfferStateException(code, message);
        }
    }

    private static void move(PlacementOffer offer, OfferStatus to) {
        if (!OfferStatusTransitions.canTransition(offer.getStatus(), to)) {
            throw new DriveExceptions.OfferStateException("OFFER_NOT_OPEN",
                    "This offer is " + label(offer.getStatus()) + " and cannot become " + label(to) + ".");
        }
        offer.setStatus(to);
        offer.setActiveInternshipLock(OfferStatusTransitions.internshipLockValue(
                offer.getOfferType(), to, offer.getStudentUserId()));
    }

    /**
     * The application follows the offer. An offer recorded outside a drive has no
     * application; one whose application already moved (a correction) is left as
     * it is and logged, rather than failing the student's answer.
     */
    private void moveApplication(PlacementOffer offer, ApplicationStatus from, ApplicationStatus to,
                                 Long actorUserId, ActorKind kind, String reason) {
        if (offer.getApplication() == null) {
            return;
        }
        JobApplication app = applicationRepository.findById(offer.getApplication().getId()).orElse(null);
        if (app == null || app.getStatus() != from || !ApplicationStatusTransitions.canTransition(from, to)) {
            log.warn("Offer {} moved to {} but its application {} is {} -- left unchanged", offer.getId(),
                    offer.getStatus(), app == null ? null : app.getId(), app == null ? null : app.getStatus());
            return;
        }
        app.setStatus(to);
        app.setUpdatedBy(actorUserId);
        applicationRepository.save(app);
        applicationService.recordHistory(app, from, to, actorUserId, kind, reason);
    }

    private MyOffer one(Long studentUserId, Long offerId) {
        offerRepository.flush();
        return myOffers(studentUserId).stream().filter(o -> o.offerId().equals(offerId)).findFirst()
                .orElseThrow(() -> new DriveExceptions.OfferStateException("OFFER_NOT_FOUND", "No such offer."));
    }

    private static String requireReason(String reason) {
        String why = reason == null ? "" : reason.trim();
        if (why.length() < 5) {
            throw new DriveExceptions.InvalidRequestException("Please give a reason (at least a few words).");
        }
        return why.length() > 500 ? why.substring(0, 500) : why;
    }

    private static String truncate(String s) {
        return s.length() <= 255 ? s : s.substring(0, 252) + "...";
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    static String label(OfferStatus s) {
        return switch (s) {
            case OFFERED -> "awaiting your answer";
            case ACCEPTED -> "accepted";
            case DECLINED -> "declined";
            case REVOKED -> "withdrawn by the company";
            case WITHDRAWN -> "withdrawn";
            case JOINED -> "joined";
            case LAPSED -> "lapsed";
        };
    }

    /** Same wording as admin-service's round notices. */
    static String opportunity(OfferType type) {
        return switch (type) {
            case PPO -> "Pre-placement offer (PPO)";
            case FULL_TIME -> "Full-time placement";
            case INTERNSHIP -> "Internship";
            case INTERNSHIP_WITH_PPO -> "Internship with PPO";
            case APPRENTICESHIP -> "Apprenticeship";
            case COMPETITION -> "Competition";
            case COMPETITION_WITH_PPO -> "Competition with PPO";
            case COMPETITION_WITH_INTERNSHIP -> "Competition with internship";
            case OTHER -> "Selected";
        };
    }
}
