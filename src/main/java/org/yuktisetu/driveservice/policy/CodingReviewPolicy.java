package org.yuktisetu.driveservice.policy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yuktisetu.core.security.UserPrincipal;

import java.util.Collection;

/**
 * Who may review flagged applications, and whose.
 *
 * Same shape as admin-service's PlacementDrivePolicy, and the same role band as
 * disqualifying an applicant there -- TnP coordinator and above. An HoD can read
 * a pool but not change who is in it, and a review decision changes exactly that.
 *
 * Scoped per STUDENT, not per drive. On a drive targeting several colleges, a
 * PCCOE coordinator reviews the PCCOE students and nobody else's: the decision is
 * one they have to be able to answer for, and they can only answer for their own
 * college. Trust-wide roles review everyone.
 */
@Slf4j
@Component
public class CodingReviewPolicy {

    private static final String IT_ADMIN = "IT_ADMIN";
    private static final String TNP_SUPER_ADMIN = "TNP_SUPER_ADMIN";
    private static final String TNP_COLLEGE_ADMIN = "TNP_COLLEGE_ADMIN";
    private static final String TNP_COORDINATOR = "TNP_COORDINATOR";

    public boolean isTrustWide(UserPrincipal actor) {
        return actor.hasRole(IT_ADMIN) || actor.hasRole(TNP_SUPER_ADMIN);
    }

    /** May the actor open this drive's review at all? Any targeted college in scope. */
    public boolean canOpenReview(UserPrincipal actor, Collection<Long> targetCollegeIds) {
        boolean allowed = isTrustWide(actor)
                || targetCollegeIds.stream().anyMatch(c -> canReviewCollege(actor, c));
        if (!allowed) {
            log.warn("Coding review denied for actorId={} roles={}: no reviewing role in target colleges {}",
                    actor.userId(), actor.roles(), targetCollegeIds);
        }
        return allowed;
    }

    /** May the actor decide for a student of this college? */
    public boolean canReviewCollege(UserPrincipal actor, Long studentCollegeId) {
        if (isTrustWide(actor)) {
            return true;
        }
        if (studentCollegeId == null) {
            // A student with no active college assignment is a data problem. Only a
            // trust-wide reviewer may decide for them, so it cannot fall between
            // two colleges' coordinators unnoticed.
            return false;
        }
        return actor.hasRoleForCollege(TNP_COLLEGE_ADMIN, studentCollegeId)
                || actor.hasRoleForCollege(TNP_COORDINATOR, studentCollegeId);
    }
}
