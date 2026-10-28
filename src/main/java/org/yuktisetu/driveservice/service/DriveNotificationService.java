package org.yuktisetu.driveservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yuktisetu.core.time.IstTime;
import org.yuktisetu.drive.model.PostingNotice;
import org.yuktisetu.drive.repository.JobPostingRepository;
import org.yuktisetu.drive.db.PostingTarget;
import org.yuktisetu.drive.model.CaptureFlagReason;
import org.yuktisetu.drive.repository.PostingTargetRepository;
import org.yuktisetu.identity.model.NotifyTarget;
import org.yuktisetu.identity.model.RoleType;
import org.yuktisetu.identity.repository.UserRepository;
import org.yuktisetu.identity.repository.UserRoleAssignmentRepository;
import org.yuktisetu.messaging.events.Dispatches;
import org.yuktisetu.messaging.events.DriveTopics;
import org.yuktisetu.messaging.events.NotificationDispatchEvent;
import org.yuktisetu.profile.model.CodingPlatform;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * drive-service's notification producer: receipts for what a student did, and
 * the one staff notice this service is in a position to send.
 *
 * Same discipline as admin-service's copy -- outbox rows only, called from
 * inside the business transaction, and nothing here ever throws. A student who
 * successfully applied one second before the deadline must not have that
 * application rolled back because a notification could not be assembled.
 *
 * The receipts are emailed rather than left in the bell for one reason: both of
 * them are things a student later disputes. "I applied and you lost it" and "I
 * never withdrew from that drive" are both arguments that a timestamped email in
 * the student's own mailbox settles and an in-app row does not.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DriveNotificationService {

    /** Placement staff only -- see the note on admin-service's equivalent. */
    private static final Set<RoleType> STAFF_AUDIENCE = Set.of(
            RoleType.TNP_COORDINATOR,
            RoleType.TNP_COLLEGE_ADMIN,
            RoleType.TNP_SUPER_ADMIN);

    private static final DateTimeFormatter DEADLINE_FORMAT =
            DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a").withZone(IstTime.ZONE);

    private final OutboxWriter outboxWriter;
    private final UserRepository userRepository;
    private final UserRoleAssignmentRepository roleAssignmentRepository;
    private final PostingTargetRepository targetRepository;
    private final JobPostingRepository postingRepository;

    @Value("${yuktisetu.portal.student-url:http://localhost:3001}")
    private String studentPortalUrl;

    @Value("${yuktisetu.portal.admin-url:http://localhost:3000}")
    private String adminPortalUrl;

    /** Receipt for an Easy Apply, queued in the same transaction as the application. */
    public void queueApplicationSubmitted(Long postingId, Long studentUserId) {
        queueStudentReceipt(postingId, studentUserId, true);
    }

    /** Receipt for a withdrawal, queued in the same transaction as the status change. */
    public void queueApplicationWithdrawn(Long postingId, Long studentUserId) {
        queueStudentReceipt(postingId, studentUserId, false);
    }

    private void queueStudentReceipt(Long postingId, Long studentUserId, boolean submitted) {
        try {
            PostingNotice posting = noticeOf(postingId);
            Optional<NotifyTarget> found = userRepository.findNotifyTarget(studentUserId);
            if (found.isEmpty() || !found.get().isAddressable()) {
                log.warn("No address for studentUserId={}; no {} receipt sent for postingId={}",
                        studentUserId, submitted ? "application" : "withdrawal", posting.id());
                return;
            }
            NotifyTarget student = found.get();

            String companyName = posting.companyName();
            String title = posting.title();
            String deadline = posting.applicationDeadline() == null
                    ? "not set"
                    : DEADLINE_FORMAT.format(posting.applicationDeadline());
            String actionUrl = studentPortalUrl + "/drives/" + posting.id();

            List<NotificationDispatchEvent.Recipient> recipients = List.of(
                    new NotificationDispatchEvent.Recipient(
                            student.userId(), student.email(), student.displayName(),
                            Map.of("studentName", student.displayName())));

            NotificationDispatchEvent event = submitted
                    ? Dispatches.applicationSubmitted(companyName, title, deadline, actionUrl, recipients)
                    : Dispatches.applicationWithdrawn(companyName, title, deadline, actionUrl, recipients);

            outboxWriter.write(
                    DriveTopics.NOTIFICATION_DISPATCH,
                    "NotificationDispatch",
                    "JobApplication",
                    posting.id(),
                    String.valueOf(studentUserId),
                    studentUserId,
                    event);

            log.info("Queued {} receipt: postingId={} studentUserId={}",
                    submitted ? "application" : "withdrawal", posting.id(), studentUserId);

        } catch (Exception e) {
            // Never propagate. An applied student whose receipt failed is still
            // applied; a rolled-back application is a student who is not.
            log.error("Could not queue the {} receipt for postingId={} studentUserId={}: {}",
                    submitted ? "application" : "withdrawal", postingId, studentUserId, e.getMessage(), e);
        }
    }

    /**
     * Tell the placement staff a pool has sealed.
     *
     * IN_APP only, and exactly one per drive. This is the handover moment -- the
     * applicant list is now final and the next step is theirs -- so it has to
     * arrive, but it arrives where they will act on it.
     *
     * Note what is deliberately NOT here: nothing notifies staff when a student
     * applies. A 400-applicant drive would produce 400 messages, and the person
     * who has to read them is the same person who has to run the drive. The
     * count in this one message is the useful form of that information.
     */
    public void queueApplicantPoolSealed(Long postingId, int applicantCount) {
        try {
            PostingNotice posting = noticeOf(postingId);
            List<NotificationDispatchEvent.Recipient> recipients = staffRecipients(postingId);
            if (recipients.isEmpty()) {
                log.warn("Pool sealed for postingId={} but there is no staff audience to tell", posting.id());
                return;
            }

            outboxWriter.write(
                    DriveTopics.NOTIFICATION_DISPATCH,
                    "NotificationDispatch",
                    "JobPosting",
                    posting.id(),
                    String.valueOf(posting.id()),
                    null,
                    Dispatches.applicantPoolSealed(
                            posting.companyName(),
                            posting.title(),
                            applicantCount,
                            adminPortalUrl + "/drives/" + posting.id(),
                            recipients));

            log.info("Queued pool-sealed notice for postingId={} ({} applicants) to {} staff member(s)",
                    posting.id(), applicantCount, recipients.size());

        } catch (Exception e) {
            log.error("Could not queue the pool-sealed notice for postingId={}: {}",
                    postingId, e.getMessage(), e);
        }
    }

    /**
     * Tell the placement staff that scoring is held on them.
     *
     * Sent once per drive, when every coding capture has settled and some students
     * need a decision. EMAIL as well as in-app (see the template's note): until the
     * TnP acts, the drive is not scored, and a hold nobody saw is indistinguishable
     * from a drive that broke.
     *
     * Never throws. The gate's state change is the record that review is needed;
     * a lost notice must not roll that back.
     */
    public void queueCodingDataReviewRequired(Long postingId, int studentsToReview,
                                              String platforms, String breakdown) {
        try {
            PostingNotice posting = noticeOf(postingId);
            List<NotificationDispatchEvent.Recipient> recipients = staffRecipients(postingId);
            if (recipients.isEmpty()) {
                log.warn("Coding review needed for postingId={} but there is no staff audience to tell",
                        posting.id());
                return;
            }

            outboxWriter.write(
                    DriveTopics.NOTIFICATION_DISPATCH,
                    "NotificationDispatch",
                    "JobPosting",
                    posting.id(),
                    String.valueOf(posting.id()),
                    null,
                    Dispatches.codingDataReviewRequired(
                            posting.companyName(),
                            posting.title(),
                            platforms,
                            studentsToReview,
                            null,
                            "Coding data could not be captured cleanly for " + studentsToReview
                                    + " application(s). " + breakdown
                                    + ". Shortlisting will run once you have reviewed them.",
                            // Straight to the review tab, which is where the
                            // decision is made -- not the drive's front page.
                            adminPortalUrl + "/drives/" + posting.id() + "/coding-review",
                            recipients));

            log.info("Queued coding-review notice for postingId={} ({} to review) to {} staff member(s)",
                    posting.id(), studentsToReview, recipients.size());

        } catch (Exception e) {
            log.error("Could not queue the coding-review notice for postingId={}: {}",
                    postingId, e.getMessage(), e);
        }
    }

    // ------------------------------------------------------- coding handle checks
    //
    // One notice per student per event, listing every drive it affects -- a typo
    // that failed on three drives is one message, not three. All three never
    // throw, for the same reason as the receipts: the capture's state is the
    // record, and a lost notice must not roll it back.

    /** Their handle could not be read and the deadline is open: check it and re-check. */
    public void queueHandleCheckNeeded(Long studentUserId, CodingPlatform platform, String handle,
                                       String outcome, Collection<Long> postingIds) {
        List<PostingNotice> drives = noticesOf(postingIds);
        String problem = "NO_HANDLE".equals(outcome)
                ? "You listed " + platform.label() + " on your profile without a username."
                : "No account named '" + handle + "' was found on " + platform.label() + ".";
        Instant earliest = drives.stream().map(PostingNotice::applicationDeadline)
                .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        queueStudentCodingNotice(studentUserId, "handle-check", platform.label(), drives, (label, recipients) ->
                Dispatches.codingHandleCheckNeeded(label, handle == null || handle.isBlank() ? "(none)" : handle,
                        problem, describeDrives(drives), formatDeadline(earliest), studentPortalUrl, recipients));
    }

    /** The re-check failed on the handle too: scored on the other platforms. */
    public void queueHandleStillFailing(Long studentUserId, CodingPlatform platform, String handle,
                                        Collection<Long> postingIds, Collection<CodingPlatform> capturedOthers) {
        List<PostingNotice> drives = noticesOf(postingIds);
        String scoredOn = capturedOthers.isEmpty()
                ? "You will be scored on your other coding platforms. If none of them can be read either, "
                        + "the TnP Cell will review your application."
                : "You will be scored on the best of your other platforms: "
                        + capturedOthers.stream().map(CodingPlatform::label).sorted()
                                .collect(Collectors.joining(", ")) + ".";
        queueStudentCodingNotice(studentUserId, "still-failing", platform.label(), drives, (label, recipients) ->
                Dispatches.codingHandleStillFailing(label, handle, scoredOn, describeDrives(drives),
                        studentPortalUrl, recipients));
    }

    /** Their application goes to TnP review, and why. */
    public void queueCaptureFlagged(Long studentUserId, Collection<CodingPlatform> platforms,
                                    CaptureFlagReason reason, Collection<Long> postingIds) {
        List<PostingNotice> drives = noticesOf(postingIds);
        String label = platforms.stream().map(CodingPlatform::label).distinct().sorted()
                .collect(Collectors.joining(", "));
        String why = switch (reason) {
            case SERVER_ERROR -> "We could not read " + label + " because of a server error, on our side or "
                    + label + "'s, not because of anything you did. A TnP member will evaluate you.";
            case NO_RECHECK -> "Your " + label + " username could not be read, and it was not re-checked "
                    + "before the deadline.";
            case AFTER_DEADLINE -> "Your " + label + " username could not be read, and the deadline had "
                    + "already passed, so there was no time to re-check it.";
            case NOTHING_LEFT -> "None of the coding platforms you listed could be read, so there is "
                    + "nothing to score you on automatically.";
        };
        queueStudentCodingNotice(studentUserId, "flagged-" + reason, label, drives, (l, recipients) ->
                Dispatches.codingCaptureFlagged(l, why, describeDrives(drives), studentPortalUrl, recipients));
    }

    private void queueStudentCodingNotice(
            Long studentUserId, String kind, String platformLabel, List<PostingNotice> drives,
            java.util.function.BiFunction<String, List<NotificationDispatchEvent.Recipient>, NotificationDispatchEvent> build) {
        if (drives.isEmpty()) {
            return;
        }
        try {
            Optional<NotifyTarget> found = userRepository.findNotifyTarget(studentUserId);
            if (found.isEmpty() || !found.get().isAddressable()) {
                log.warn("No address for studentUserId={}; no coding {} notice for {}",
                        studentUserId, kind, platformLabel);
                return;
            }
            NotifyTarget student = found.get();
            List<NotificationDispatchEvent.Recipient> recipients = List.of(
                    new NotificationDispatchEvent.Recipient(
                            student.userId(), student.email(), student.displayName(),
                            Map.of("studentName", student.displayName())));

            PostingNotice first = drives.get(0);
            outboxWriter.write(
                    DriveTopics.NOTIFICATION_DISPATCH,
                    "NotificationDispatch",
                    "JobApplication",
                    first.id(),
                    String.valueOf(studentUserId),
                    studentUserId,
                    build.apply(platformLabel, recipients));

            log.info("Queued coding {} notice: studentUserId={} platform={} drives={}",
                    kind, studentUserId, platformLabel, drives.stream().map(PostingNotice::id).toList());

        } catch (Exception e) {
            log.error("Could not queue the coding {} notice for studentUserId={} platform={}: {}",
                    kind, studentUserId, platformLabel, e.getMessage(), e);
        }
    }

    /** Recipients per dispatch message; large audiences are chunked, never one message each. */
    private static final int RECIPIENT_CHUNK = 500;

    /**
     * The shortlist is out: one batched notice to the shortlisted, one to the
     * rest. Queued in the publish transaction; never throws.
     */
    public void queueShortlistResult(Long postingId, Collection<Long> shortlisted,
                                     Collection<Long> notShortlisted, boolean scoresVisible) {
        try {
            PostingNotice posting = noticeOf(postingId);
            String actionUrl = studentPortalUrl + "/drives/" + posting.id();
            queueShortlistChunks(posting, shortlisted, true, scoresVisible, actionUrl);
            queueShortlistChunks(posting, notShortlisted, false, scoresVisible, actionUrl);
            log.info("Queued shortlist result: postingId={} shortlisted={} notShortlisted={} scoresVisible={}",
                    postingId, shortlisted.size(), notShortlisted.size(), scoresVisible);
        } catch (Exception e) {
            log.error("Could not queue the shortlist result for postingId={}: {}", postingId, e.getMessage(), e);
        }
    }

    private void queueShortlistChunks(PostingNotice posting, Collection<Long> userIds, boolean shortlisted,
                                      boolean scoresVisible, String actionUrl) {
        if (userIds.isEmpty()) {
            return;
        }
        List<NotificationDispatchEvent.Recipient> all = new ArrayList<>();
        for (NotifyTarget t : userRepository.findNotifyTargets(userIds)) {
            if (t.isAddressable()) {
                all.add(new NotificationDispatchEvent.Recipient(
                        t.userId(), t.email(), t.displayName(), Map.of("studentName", t.displayName())));
            }
        }
        for (int i = 0; i < all.size(); i += RECIPIENT_CHUNK) {
            List<NotificationDispatchEvent.Recipient> chunk = all.subList(i, Math.min(all.size(), i + RECIPIENT_CHUNK));
            outboxWriter.write(
                    DriveTopics.NOTIFICATION_DISPATCH,
                    "NotificationDispatch",
                    "JobPosting",
                    posting.id(),
                    String.valueOf(posting.id()),
                    null,
                    Dispatches.shortlistResult(posting.companyName(), posting.title(), shortlisted,
                            scoresVisible, actionUrl, List.copyOf(chunk)));
        }
    }

    /**
     * A sealed drive was reopened. One batched notice per audience, because each
     * is told something different: the previously shortlisted that they are under
     * re-review, applicants that their application stands, and eligible students
     * who never applied that they can now.
     */
    public void queueDriveReopened(Long postingId, Instant newDeadline,
                                   Collection<Long> previouslyShortlisted,
                                   Collection<Long> applicants,
                                   Collection<Long> eligibleNotApplied) {
        try {
            PostingNotice posting = noticeOf(postingId);
            String actionUrl = studentPortalUrl + "/drives/" + posting.id();
            String deadline = formatDeadline(newDeadline);
            queueReopenChunks(posting, previouslyShortlisted, Dispatches.ReopenAudience.PREVIOUSLY_SHORTLISTED, deadline, actionUrl);
            queueReopenChunks(posting, applicants, Dispatches.ReopenAudience.APPLICANT, deadline, actionUrl);
            queueReopenChunks(posting, eligibleNotApplied, Dispatches.ReopenAudience.ELIGIBLE, deadline, actionUrl);
            log.info("Queued drive-reopened notices: postingId={} previouslyShortlisted={} applicants={} eligible={}",
                    postingId, previouslyShortlisted.size(), applicants.size(), eligibleNotApplied.size());
        } catch (Exception e) {
            log.error("Could not queue the drive-reopened notices for postingId={}: {}", postingId, e.getMessage(), e);
        }
    }

    private void queueReopenChunks(PostingNotice posting, Collection<Long> userIds,
                                   Dispatches.ReopenAudience audience, String deadline, String actionUrl) {
        if (userIds.isEmpty()) {
            return;
        }
        List<NotificationDispatchEvent.Recipient> all = new ArrayList<>();
        for (NotifyTarget t : userRepository.findNotifyTargets(userIds)) {
            if (t.isAddressable()) {
                all.add(new NotificationDispatchEvent.Recipient(
                        t.userId(), t.email(), t.displayName(), Map.of("studentName", t.displayName())));
            }
        }
        for (int i = 0; i < all.size(); i += RECIPIENT_CHUNK) {
            List<NotificationDispatchEvent.Recipient> chunk = all.subList(i, Math.min(all.size(), i + RECIPIENT_CHUNK));
            outboxWriter.write(
                    DriveTopics.NOTIFICATION_DISPATCH,
                    "NotificationDispatch",
                    "JobPosting",
                    posting.id(),
                    String.valueOf(posting.id()),
                    null,
                    Dispatches.driveReopened(posting.companyName(), posting.title(), deadline, audience,
                            actionUrl, List.copyOf(chunk)));
        }
    }

    /** "Graduate Engineer at Gate Co; SDE Intern at Acme" */
    private static String describeDrives(Collection<PostingNotice> drives) {
        return drives.stream()
                .map(p -> p.title() + " at " + p.companyName())
                .collect(Collectors.joining("; "));
    }

    /** Placement staff for the colleges a posting targets, addressable ones only. */
    private List<NotificationDispatchEvent.Recipient> staffRecipients(Long postingId) {
        List<Long> collegeIds = targetRepository.findByPosting_IdAndIsDeletedFalse(postingId).stream()
                .map(PostingTarget::getCollegeId)
                .distinct()
                .toList();
        if (collegeIds.isEmpty()) {
            return List.of();
        }
        List<NotifyTarget> staff = roleAssignmentRepository.findStaffAudience(STAFF_AUDIENCE, collegeIds);
        List<NotificationDispatchEvent.Recipient> recipients = new ArrayList<>(staff.size());
        for (NotifyTarget t : staff) {
            if (!t.isAddressable()) {
                continue;
            }
            recipients.add(new NotificationDispatchEvent.Recipient(
                    t.userId(), t.email(), t.displayName(), Map.of("staffName", t.displayName())));
        }
        return recipients;
    }

    /** Kept so the formatter has one owner in this service. */
    String formatDeadline(Instant deadline) {
        return deadline == null ? "not set" : DEADLINE_FORMAT.format(deadline);
    }

    /**
     * A drive's facts as plain values, read fresh by id. Never take a JobPosting
     * entity here: its company is a lazy proxy that throws once the entity is
     * detached (a cleared persistence context, a finished transaction, another
     * thread), and the catch-all in every method above would swallow the failure
     * and nobody would be told.
     */
    private PostingNotice noticeOf(Long postingId) {
        return postingRepository.findNoticeById(postingId)
                .orElseThrow(() -> new IllegalStateException("Posting " + postingId + " not found for notification"));
    }

    private List<PostingNotice> noticesOf(Collection<Long> postingIds) {
        if (postingIds == null || postingIds.isEmpty()) {
            return List.of();
        }
        return postingRepository.findNoticesByIds(postingIds);
    }
}
