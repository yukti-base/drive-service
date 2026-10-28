package org.yuktisetu.driveservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.yuktisetu.profile.model.CodingPlatform;

/**
 * A student re-checking one platform's handle, unchanged or corrected.
 *
 * The pattern covers what LeetCode, Codeforces and CodeChef allow in a username.
 * Its real job is catching the commonest mistake: pasting the profile link.
 */
public record CodingRecheckRequest(
        @NotNull CodingPlatform platform,
        @NotBlank
        @Pattern(regexp = "^\\s*[A-Za-z0-9_.\\-]{1,64}\\s*$",
                message = "Enter just the username -- not the profile link, and no spaces.")
        String handle
) {}
