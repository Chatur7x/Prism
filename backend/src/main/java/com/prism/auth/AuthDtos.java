package com.prism.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AuthDtos() {

    public record RegisterRequest(
            @NotBlank
            @Pattern(regexp = "^[a-zA-Z0-9_.-]{3,64}$",
                    message = "must be 3-64 characters of letters, digits, '_', '.', or '-'")
            String username,

            @NotBlank @Email @Size(max = 320)
            String email,

            @NotBlank
            @Size(min = 12, max = 128, message = "must be between 12 and 128 characters")
            @Pattern(regexp = ".*(?=.*[A-Za-z])(?=.*\\d).*", message = "must contain both letters and digits")
            String password) {
    }

    public record LoginRequest(
            @NotBlank @Size(max = 320) String username,
            @NotBlank @Size(max = 128) String password) {
    }

    public record ChangePasswordRequest(
            @NotBlank @Size(max = 128) String currentPassword,
            @NotBlank
            @Size(min = 12, max = 128, message = "must be between 12 and 128 characters")
            @Pattern(regexp = ".*(?=.*[A-Za-z])(?=.*\\d).*", message = "must contain both letters and digits")
            String newPassword) {
    }
}
