package org.yuktisetu.driveservice.exception;

import org.springframework.http.HttpStatus;
import org.yuktisetu.core.exception.ApiException;
import org.yuktisetu.core.exception.ConflictException;
import org.yuktisetu.core.exception.NotFoundException;

import java.util.List;

/**
 * One holder class, static nested subclasses of core's exception types.
 *
 * No @RestControllerAdvice anywhere in this service: core's CoreExceptionHandler
 * already maps ApiException, MethodArgumentNotValidException and Exception, and
 * re-declaring any of those would fail at request time with "Ambiguous
 * @ExceptionHandler method mapped".
 */
public final class DriveExceptions {

    private DriveExceptions() {}

    /**
     * Returned as 404, never 403, when a student asks for a drive they are not
     * eligible for. A 403 would confirm the drive exists, letting anyone
     * enumerate every posting id and learn about drives they were deliberately
     * not shown.
     */
    public static class DriveNotVisibleException extends NotFoundException {
        public DriveNotVisibleException(Long postingId) {
            super("JobPosting", postingId);
        }
    }

    public static class ApplicationWindowClosedException extends ConflictException {
        public ApplicationWindowClosedException(String detail) {
            super("APPLICATION_WINDOW_CLOSED", detail);
        }
    }

    public static class AlreadyAppliedException extends ConflictException {
        public AlreadyAppliedException() {
            super("ALREADY_APPLIED", "You have already applied to this drive.");
        }
    }

    public static class NotAppliedException extends ConflictException {
        public NotAppliedException() {
            super("NOT_APPLIED", "You have no active application to this drive.");
        }
    }

    /**
     * Eligibility is re-checked live at apply time, so a student who was eligible
     * when the drive was published can fail here -- their CGPA changed, an offer
     * landed, or the TnP edited the criteria. The failure codes are returned so
     * the reason is visible rather than a bare refusal.
     */
    public static class EligibilityChangedException extends ConflictException {
        public EligibilityChangedException(List<String> failureCodes) {
            super("ELIGIBILITY_CHANGED",
                    "You no longer meet this drive's criteria: " + String.join(", ", failureCodes));
        }
    }

    public static class ApplicationLimitReachedException extends ConflictException {
        public ApplicationLimitReachedException(int max) {
            super("APPLICATION_LIMIT_REACHED",
                    "This drive has reached its cap of " + max + " applications.");
        }
    }

    public static class InsufficientAuthorityException extends ApiException {
        public InsufficientAuthorityException() {
            super(HttpStatus.FORBIDDEN, "INSUFFICIENT_AUTHORITY",
                    "Your role is not permitted to perform this action.");
        }
    }

    public static class ScopeViolationException extends ApiException {
        public ScopeViolationException() {
            super(HttpStatus.FORBIDDEN, "SCOPE_VIOLATION",
                    "This drive targets a college outside your assigned scope.");
        }
    }

    public static class StudentProfileMissingException extends ConflictException {
        public StudentProfileMissingException() {
            super("STUDENT_PROFILE_MISSING",
                    "No student profile exists for this account. Complete your profile before applying.");
        }
    }

    public static class PostingNotReadyException extends ConflictException {
        public PostingNotReadyException(String detail) {
            super("POSTING_NOT_READY", detail);
        }
    }

    /**
     * The catch-all for an unexpected failure on the apply path.
     *
     * Deliberately vague to the student and specific in the log: whatever broke,
     * they need to know their application did not go through and that retrying is
     * reasonable, not a stack trace or a database message.
     */
    public static class ApplicationFailedException extends ApiException {
        public ApplicationFailedException() {
            super(HttpStatus.INTERNAL_SERVER_ERROR, "APPLICATION_FAILED",
                    "Your application could not be submitted right now. Please try again in a moment.");
        }
    }

    /** The drive is already READY (the engine has started) or was cancelled. */
    public static class ReviewClosedException extends ConflictException {
        public ReviewClosedException(org.yuktisetu.drive.model.ScoringGateState state) {
            super("CODING_REVIEW_CLOSED", state == org.yuktisetu.drive.model.ScoringGateState.READY
                    ? "Every flagged application on this drive has been decided and shortlisting has started. Decisions can no longer be changed."
                    : "This drive was cancelled, so there is nothing left to review.");
        }
    }

    /** A coding re-check that cannot happen: none is waiting, it was used, or the deadline passed. */
    public static class RecheckUnavailableException extends ConflictException {
        public RecheckUnavailableException(String detail) {
            super("CODING_RECHECK_UNAVAILABLE", detail);
        }
    }

    /** The scoring or shortlist step asked for is not available in the drive's current state. */
    public static class ScoringStateException extends ConflictException {
        public ScoringStateException(String code, String detail) {
            super(code, detail);
        }
    }

    public static class InvalidRequestException extends ApiException {
        public InvalidRequestException(String message) {
            super(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
        }
    }
}
