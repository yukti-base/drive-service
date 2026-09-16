package org.yuktisetu.driveservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.core.cache.CacheNames;
import org.yuktisetu.core.exception.ApiException;
import org.yuktisetu.driveservice.dto.MyEligibilityResponse;
import org.yuktisetu.driveservice.dto.StudentDriveDetailResponse;
import org.yuktisetu.driveservice.dto.StudentDriveSummaryResponse;
import org.yuktisetu.driveservice.exception.DriveExceptions;
import org.yuktisetu.db.Company;
import org.yuktisetu.db.JobApplication;
import org.yuktisetu.db.JobPosting;
import org.yuktisetu.db.PostingEligibilityCriteria;
import org.yuktisetu.db.PostingSkill;
import org.yuktisetu.model.ApplicationStatus;
import org.yuktisetu.model.PostingStatus;
import org.yuktisetu.model.PostingStatusTransitions;
import org.yuktisetu.repository.JobApplicationRepository;
import org.yuktisetu.repository.PostingEligibilityCriteriaRepository;
import org.yuktisetu.repository.PostingEligibleStudentRepository;
import org.yuktisetu.repository.PostingLocationRepository;
import org.yuktisetu.repository.PostingSkillRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a student sees.
 *
 * Every read goes through posting_eligible_students, so eligibility is enforced by
 * the shape of the query rather than by a filter someone can forget to apply. A
 * drive the student is not eligible for is not hidden -- it is absent from the
 * result, and asking for it directly returns 404.
 *
 * This is the hottest path in the system: when a drive is announced, a couple of
 * thousand students open this list within a minute or two. It is therefore written
 * to a fixed query budget -- the list costs exactly two queries regardless of how
 * many drives a student can see.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudentDriveService {

    private static final List<PostingStatus> OPEN_STATUSES = List.of(PostingStatus.PUBLISHED);
    private static final List<PostingStatus> CLOSED_STATUSES = List.of(
            PostingStatus.APPLICATIONS_CLOSED, PostingStatus.IN_PROGRESS, PostingStatus.COMPLETED);
    private static final List<PostingStatus> ALL_VISIBLE_STATUSES = List.of(
            PostingStatus.PUBLISHED, PostingStatus.APPLICATIONS_CLOSED,
            PostingStatus.IN_PROGRESS, PostingStatus.COMPLETED);

    private final PostingEligibleStudentRepository eligibleStudentRepository;
    private final JobApplicationRepository applicationRepository;
    private final PostingSkillRepository skillRepository;
    private final PostingLocationRepository locationRepository;
    private final PostingEligibilityCriteriaRepository criteriaRepository;
    private final StudentEligibilityService eligibilityService;

    /**
     * The student's drive list. Two queries, always.
     *
     * The application statuses are fetched for every visible posting in a single
     * batched call and indexed into a HashMap. The obvious implementation -- ask
     * "have I applied?" per row while mapping -- is an N+1 that turns one screen
     * into thirty-one queries, and multiplies by every student refreshing at once
     * when a drive drops.
     */
    @Transactional(readOnly = true)
    public List<StudentDriveSummaryResponse> listForStudent(Long studentUserId, String tab) {
        try {
            Instant now = Instant.now();
            List<PostingStatus> statuses = statusesFor(tab);

            // Query 1: the visible postings, with company join-fetched.
            List<JobPosting> postings =
                    eligibleStudentRepository.findVisiblePostingsForStudent(studentUserId, statuses, now);
            if (postings.isEmpty()) {
                return List.of();
            }

            List<Long> postingIds = new ArrayList<>(postings.size());
            for (JobPosting p : postings) {
                postingIds.add(p.getId());
            }

            // Query 2: this student's status on all of them at once. O(1) lookups
            // from here on.
            Map<Long, ApplicationStatus> myStatuses = new HashMap<>(Math.max(16, postings.size() * 2));
            for (Object[] row : applicationRepository.findStatusesForStudent(studentUserId, postingIds)) {
                myStatuses.put(((Number) row[0]).longValue(), (ApplicationStatus) row[1]);
            }

            List<StudentDriveSummaryResponse> out = new ArrayList<>(postings.size());
            for (JobPosting p : postings) {
                out.add(toSummary(p, myStatuses.get(p.getId()), now));
            }
            return out;

        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to list drives for studentUserId={} tab={}: {}", studentUserId, tab, e.getMessage(), e);
            throw new DriveExceptions.InvalidRequestException(
                    "Could not load your drives right now. Please try again.");
        }
    }

    /**
     * Full detail for one drive.
     *
     * Returns 404, never 403, when the student is not in the eligible set: a 403
     * would confirm the posting exists, letting anyone walk the id space and learn
     * about drives they were deliberately not shown, including other colleges'.
     */
    @Transactional(readOnly = true)
    public StudentDriveDetailResponse getForStudent(Long postingId, Long studentUserId) {
        try {
            JobPosting posting = requireVisible(postingId, studentUserId);
            Instant now = Instant.now();

            List<PostingSkill> skills = skillRepository.findByPosting_IdAndIsDeletedFalse(postingId);
            List<String> mandatory = new ArrayList<>();
            List<String> preferred = new ArrayList<>();
            for (PostingSkill s : skills) {
                (s.isMandatory() ? mandatory : preferred).add(s.getSkill());
            }

            List<String> locations = locationRepository.findByPosting_IdAndIsDeletedFalse(postingId)
                    .stream()
                    .map(l -> l.getState() == null ? l.getCity() : l.getCity() + ", " + l.getState())
                    .toList();

            Optional<JobApplication> mine = applicationRepository
                    .findByPosting_IdAndStudent_IdAndIsDeletedFalse(postingId, studentUserId);
            Company company = posting.getCompany();

            return new StudentDriveDetailResponse(
                    posting.getId(),
                    company.getName(), company.getLogoUrl(), company.getWebsite(), company.getDescription(),
                    posting.getTitle(), posting.getJobType(), posting.getWorkMode(),
                    posting.getDescription(), posting.getResponsibilities(),
                    posting.getCtcMin(), posting.getCtcMax(), posting.getCtcCurrency(),
                    posting.getStipendAmount(), posting.getStipendPeriod(),
                    posting.getInternshipDurationMonths(), posting.isPpoPossible(),
                    posting.getBondMonths(), posting.getVacancyCount(),
                    mandatory, preferred, locations,
                    posting.getStatus(), posting.getApplicationOpensAt(), posting.getApplicationDeadline(),
                    posting.getDriveDate(), posting.getDriveVenue(),
                    isApplyOpen(posting, now),
                    mine.map(JobApplication::getStatus).orElse(null),
                    mine.map(JobApplication::getAppliedAt).orElse(null),
                    criteriaSummary(postingId),
                    now);

        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to load drive id={} for studentUserId={}: {}", postingId, studentUserId, e.getMessage(), e);
            throw new DriveExceptions.InvalidRequestException(
                    "Could not load this drive right now. Please try again.");
        }
    }

    /**
     * "Why can't I apply?" answered to the student directly, using the same live
     * check the apply endpoint runs -- so the reason shown is the reason that would
     * actually block them, not a stale snapshot.
     */
    @Transactional(readOnly = true)
    public MyEligibilityResponse myEligibility(Long postingId, Long studentUserId) {
        try {
            JobPosting posting = requireVisible(postingId, studentUserId);
            List<String> failures = eligibilityService.check(posting, studentUserId);
            return new MyEligibilityResponse(postingId, failures.isEmpty(), failures);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to compute eligibility for postingId={} studentUserId={}: {}",
                    postingId, studentUserId, e.getMessage(), e);
            throw new DriveExceptions.InvalidRequestException(
                    "Could not check your eligibility right now. Please try again.");
        }
    }

    /** One indexed lookup rather than fetching the whole visible list and filtering it. */
    JobPosting requireVisible(Long postingId, Long studentUserId) {
        return eligibleStudentRepository
                .findVisiblePostingForStudent(studentUserId, postingId)
                .filter(p -> ALL_VISIBLE_STATUSES.contains(p.getStatus()))
                .orElseThrow(() -> {
                    log.debug("Posting id={} not visible to studentUserId={}", postingId, studentUserId);
                    return new DriveExceptions.DriveNotVisibleException(postingId);
                });
    }

    /**
     * The server's own verdict on whether the apply button should be live.
     *
     * Sent to the client rather than derived there, because the deadline is enforced
     * against the server clock; a browser with a slow clock would otherwise render an
     * enabled button whose every press fails.
     */
    static boolean isApplyOpen(JobPosting posting, Instant now) {
        return PostingStatusTransitions.isAcceptingApplications(posting.getStatus())
                && (posting.getApplicationOpensAt() == null || !now.isBefore(posting.getApplicationOpensAt()))
                && now.isBefore(posting.getApplicationDeadline());
    }

    private List<PostingStatus> statusesFor(String tab) {
        if (tab == null) {
            return OPEN_STATUSES;
        }
        return switch (tab.toLowerCase()) {
            case "closed" -> CLOSED_STATUSES;
            case "all" -> ALL_VISIBLE_STATUSES;
            default -> OPEN_STATUSES;
        };
    }

    private StudentDriveSummaryResponse toSummary(JobPosting p, ApplicationStatus myStatus, Instant now) {
        Company c = p.getCompany();
        return new StudentDriveSummaryResponse(
                p.getId(), c.getName(), c.getLogoUrl(), p.getTitle(),
                p.getJobType(), p.getWorkMode(),
                p.getCtcMin(), p.getCtcMax(), p.getCtcCurrency(), p.getStipendAmount(),
                p.getStatus(), p.getApplicationDeadline(), p.getDriveDate(),
                isApplyOpen(p, now), myStatus, now);
    }

    /**
     * The gate in plain terms, for the student to read before applying. Cached --
     * it is rendered on every detail view and cannot change once published, since
     * criteria are only editable while a posting is a draft.
     *
     * Only ENABLED criteria appear; listing a disabled one would misrepresent the bar.
     */
    @Cacheable(cacheNames = CacheNames.POSTING_DETAIL, key = "'criteria:' + #postingId")
    public List<String> criteriaSummary(Long postingId) {
        Optional<PostingEligibilityCriteria> maybe =
                criteriaRepository.findByPosting_IdAndIsDeletedFalse(postingId);
        if (maybe.isEmpty()) {
            return List.of();
        }
        PostingEligibilityCriteria c = maybe.get();
        List<String> out = new ArrayList<>(8);
        if (c.isAggregateCgpaEnabled()) {
            out.add("Minimum CGPA " + c.getMinAggregateCgpa());
        }
        if (c.isTenthEnabled()) {
            out.add("Minimum 10th " + c.getMinTenthPercentage() + "%");
        }
        if (c.isTwelfthEnabled()) {
            out.add("Minimum 12th " + c.getMinTwelfthPercentage() + "%");
        }
        if (c.isDiplomaEnabled()) {
            out.add("Minimum diploma " + c.getMinDiplomaPercentage() + "%");
        }
        if (c.isActiveBacklogsEnabled()) {
            out.add(c.getMaxActiveBacklogs() == 0
                    ? "No active backlogs"
                    : "At most " + c.getMaxActiveBacklogs() + " active backlogs");
        }
        if (c.isTotalBacklogsEnabled()) {
            out.add("At most " + c.getMaxTotalBacklogs() + " backlogs in total");
        }
        if (c.isGapYearsEnabled()) {
            out.add(c.getMaxGapYears() == 0
                    ? "No academic gap years"
                    : "At most " + c.getMaxGapYears() + " gap years");
        }
        if (c.isCocubesEnabled()) {
            out.add("Minimum CoCubes score " + c.getMinCocubesScore());
        }
        if (c.isSemesterCgpaEnabled()) {
            out.add("Semester-wise GPA minimums apply");
        }
        if (c.getCriteriaNote() != null && !c.getCriteriaNote().isBlank()) {
            out.add(c.getCriteriaNote());
        }
        return out;
    }
}
