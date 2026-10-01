package com.prism.user;

import java.time.Instant;

/** Safe projection of a user. Never carries the password hash. */
public record UserSummary(Long id, String username, String email, Role role, boolean enabled, Instant createdAt) {

    public static UserSummary from(User user) {
        return new UserSummary(user.getId(), user.getUsername(), user.getEmail(),
                user.getRole(), user.isEnabled(), user.getCreatedAt());
    }
}
