package org.yuktisetu.driveservice.dto;

import org.yuktisetu.drive.model.ApplicationStatus;
import org.yuktisetu.drive.model.JobType;
import org.yuktisetu.drive.model.PostingStatus;
import org.yuktisetu.drive.model.WorkMode;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A drive as it appears in the student's list. Includes the student's own
 * application status so the UI needs no second call to decide whether to render
 * "Easy Apply" or "Applied".
 *
 * applyOpen and serverTime are both here on purpose: the apply button must
 * enable and disable on the SERVER clock, never the browser's, because the
 * deadline is enforced server-side and a student whose laptop clock is slow would
 * otherwise see an enabled button that always fails.
 */
public record StudentDriveSummaryResponse(
        Long postingId,
        String companyName,
        String companyLogoUrl,
        String title,
        JobType jobType,
        WorkMode workMode,
        BigDecimal ctcMin,
        BigDecimal ctcMax,
        String ctcCurrency,
        BigDecimal stipendAmount,
        PostingStatus status,
        Instant applicationDeadline,
        Instant driveDate,
        boolean applyOpen,
        ApplicationStatus myApplicationStatus,
        Instant serverTime
) {}
