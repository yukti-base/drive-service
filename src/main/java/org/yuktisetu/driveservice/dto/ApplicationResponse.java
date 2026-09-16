package org.yuktisetu.driveservice.dto;

import org.yuktisetu.model.ApplicationSource;
import org.yuktisetu.model.ApplicationStatus;

import java.time.Instant;

public record ApplicationResponse(
        Long applicationId,
        Long postingId,
        String companyName,
        String title,
        Long studentUserId,
        ApplicationStatus status,
        ApplicationSource source,
        Instant appliedAt,
        Instant withdrawnAt,
        Instant sealedAt,
        Instant applicationDeadline
) {}
