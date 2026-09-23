package org.yuktisetu.driveservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.core.cache.CacheNames;
import org.yuktisetu.core.exception.ApiException;
import org.yuktisetu.driveservice.exception.DriveExceptions;
import org.yuktisetu.drive.db.JobPosting;
import org.yuktisetu.drive.db.PostingEligibilityCriteria;
import org.yuktisetu.drive.db.PostingReapplicationPolicy;
import org.yuktisetu.profile.db.StudentProfile;
import org.yuktisetu.drive.model.CandidateFacts;
import org.yuktisetu.drive.model.CandidateFactsFactory;
import org.yuktisetu.drive.model.CriteriaSnapshot;
import org.yuktisetu.drive.model.CriteriaSnapshotFactory;
import org.yuktisetu.drive.model.EligibilityEvaluator;
import org.yuktisetu.drive.model.EligibilityOutcome;
import org.yuktisetu.drive.model.ReapplicationRules;
import org.yuktisetu.drive.repository.DrivePlacedStudentDecisionRepository;
import org.yuktisetu.drive.repository.EligibilityCandidateRepository;
import org.yuktisetu.drive.repository.PlacementOfferRepository;
import org.yuktisetu.drive.repository.PostingAptitudeCriterionRepository;
import org.yuktisetu.drive.repository.PostingCustomCriterionRepository;
import org.yuktisetu.drive.repository.PostingEligibilityCriteriaRepository;
import org.yuktisetu.drive.repository.PostingReapplicationPolicyRepository;
import org.yuktisetu.drive.repository.PostingSemesterCgpaCriterionRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Re-checks ONE student against ONE drive, live.
 *
 * The bulk cohort resolution lives in admin-service; this is its single-student
 * counterpart, and both call the same pure rules in drive-dal so they cannot
 * disagree. That matters more than the code sharing: if they diverged, a student
 * would appear in the eligible list and then be refused on apply with no
 * explanation anyone could give them.
 *
 * Why re-check at all when posting_eligible_students already says yes: that row may
 * be days old. In between the student's CGPA could have been re-synced from the
 * ERP, an offer could have landed, or the TnP could have edited the criteria. The
 * index decides visibility; this decides the right to apply.
 *
 * Cost per call: the criteria snapshot is cached, leaving three queries -- profile,
 * live offers, admin decision -- plus one more only when the drive actually has
 * dynamic-field criteria.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudentEligibilityService {

    private final PostingEligibilityCriteriaRepository criteriaRepository;
    private final PostingSemesterCgpaCriterionRepository semesterRepository;
    private final PostingAptitudeCriterionRepository aptitudeRepository;
    private final PostingCustomCriterionRepository customRepository;
    private final PostingReapplicationPolicyRepository policyRepository;
    private final EligibilityCandidateRepository candidateRepository;
    private final PlacementOfferRepository offerRepository;
    private final DrivePlacedStudentDecisionRepository decisionRepository;

    /**
     * @return failure codes; empty means this student may apply right now
     */
    @Transactional(readOnly = true)
    public List<String> check(JobPosting posting, Long studentUserId) {
        try {
            Long postingId = posting.getId();

            PostingEligibilityCriteria criteriaEntity = criteriaRepository
                    .findByPosting_IdAndIsDeletedFalse(postingId)
                    .orElseThrow(() -> new DriveExceptions.PostingNotReadyException(
                            "This drive has no eligibility criteria set."));
            PostingReapplicationPolicy policy = policyRepository
                    .findByPosting_IdAndIsDeletedFalse(postingId)
                    .orElseThrow(() -> new DriveExceptions.PostingNotReadyException(
                            "This drive has no re-application policy set."));

            CriteriaSnapshot snapshot = snapshotFor(postingId, criteriaEntity);

            StudentProfile profile = candidateRepository
                    .findProfilesByUserIds(List.of(studentUserId)).stream()
                    .findFirst()
                    .orElseThrow(DriveExceptions.StudentProfileMissingException::new);

            CandidateFacts facts = CandidateFactsFactory.of(
                    profile, null, null, null, customValues(snapshot, studentUserId));

            EligibilityOutcome outcome = EligibilityEvaluator.evaluate(snapshot, facts);

            List<String> failures = new ArrayList<>(outcome.failureCodes());
            failures.addAll(ReapplicationRules.check(
                    posting, policy,
                    offerRepository.findLiveOffersForStudents(
                            List.of(studentUserId), ReapplicationRules.liveStatuses()),
                    decisionRepository
                            .findByPosting_IdAndStudent_IdAndIsDeletedFalse(posting.getId(), studentUserId)
                            .orElse(null)));

            if (failures.isEmpty() && outcome.needsReview()) {
                failures.add("NEEDS_REVIEW");
            }
            return failures;

        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Eligibility re-check failed for postingId={} studentUserId={}: {}",
                    posting.getId(), studentUserId, e.getMessage(), e);
            // Fail CLOSED. An unreadable gate must not become an open one -- letting
            // an application through because the check crashed is how an ineligible
            // student ends up in a company's shortlist.
            throw new DriveExceptions.PostingNotReadyException(
                    "Your eligibility for this drive could not be verified right now. Please try again shortly.");
        }
    }

    /**
     * The assembled criteria for a drive, cached.
     *
     * Read on every apply and every "why can't I apply" call, and it cannot change
     * once a posting is published -- criteria are only editable in DRAFT. Without
     * the cache each of those requests costs four queries just to rebuild a gate
     * that has not moved. admin-service evicts this key when the TnP edits criteria.
     */
    @Cacheable(cacheNames = CacheNames.POSTING_CRITERIA, key = "#postingId")
    public CriteriaSnapshot snapshotFor(Long postingId, PostingEligibilityCriteria criteriaEntity) {
        Long criteriaId = criteriaEntity.getId();
        return CriteriaSnapshotFactory.of(
                criteriaEntity,
                semesterRepository.findByCriteria_IdAndIsDeletedFalse(criteriaId),
                aptitudeRepository.findByCriteria_IdAndIsDeletedFalse(criteriaId),
                customRepository.findByCriteria_IdAndIsDeletedFalse(criteriaId));
    }

    private Map<String, String> customValues(CriteriaSnapshot snapshot, Long studentUserId) {
        List<String> keys = CriteriaSnapshotFactory.dynamicFieldKeys(snapshot);
        if (keys.isEmpty()) {
            // Most drives have no dynamic-field criteria, so skip the query rather
            // than issue one with an empty IN list.
            return Map.of();
        }
        Map<String, String> values = new HashMap<>(Math.max(4, keys.size() * 2));
        for (Object[] row : candidateRepository.findDynamicFieldValues(List.of(studentUserId), keys)) {
            String fieldKey = (String) row[1];
            String value = firstNonNull(row[2], row[3], row[4], row[5]);
            if (value != null) {
                values.put(fieldKey, value);
            }
        }
        return values;
    }

    private String firstNonNull(Object... candidates) {
        for (Object c : candidates) {
            if (c != null) {
                return String.valueOf(c);
            }
        }
        return null;
    }
}
