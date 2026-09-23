package org.yuktisetu.driveservice.dto;

import org.yuktisetu.drive.model.ApplicationStatus;
import org.yuktisetu.drive.model.JobType;
import org.yuktisetu.drive.model.PostingStatus;
import org.yuktisetu.drive.model.StipendPeriod;
import org.yuktisetu.drive.model.WorkMode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The full drive view for an eligible student.
 *
 * Exposes the criteria the drive was gated on, because transparency about the
 * shortlisting process is a product commitment here rather than a setting: a
 * student who can read the bar is a student who does not have to ask the TnP
 * why they were filtered.
 */
public record StudentDriveDetailResponse(
        Long postingId,
        String companyName,
        String companyLogoUrl,
        String companyWebsite,
        String companyDescription,
        String title,
        JobType jobType,
        WorkMode workMode,
        String description,
        String responsibilities,
        BigDecimal ctcMin,
        BigDecimal ctcMax,
        String ctcCurrency,
        BigDecimal stipendAmount,
        StipendPeriod stipendPeriod,
        Integer internshipDurationMonths,
        boolean ppoPossible,
        Integer bondMonths,
        Integer vacancyCount,
        List<String> mandatorySkills,
        List<String> preferredSkills,
        List<String> locations,
        PostingStatus status,
        Instant applicationOpensAt,
        Instant applicationDeadline,
        Instant driveDate,
        String driveVenue,
        boolean applyOpen,
        ApplicationStatus myApplicationStatus,
        Instant myAppliedAt,
        List<String> criteriaSummary,
        Instant serverTime
) {}
