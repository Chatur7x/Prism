package com.prism.auth;

import com.prism.user.UserSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Registration, login, token refresh")
public class AuthController {

    private final AuthService auth;
    private final AuthService.AuthenticatedUserProvider currentUser;

    public AuthController(AuthService auth, AuthService.AuthenticatedUserProvider currentUser) {
        this.auth = auth;
        this.currentUser = currentUser;
    }

    @PostMapping("/register")
    @SecurityRequirements
    @Operation(summary = "Register a new ANALYST account",
            description = "Self-registration always yields the ANALYST role. VERIFIER and ADMIN "
                    + "accounts are provisioned by an administrator.")
    public ResponseEntity<AuthService.AuthResponse> register(@Valid @RequestBody AuthDtos.RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(auth.register(request.username(), request.email(), request.password()));
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(summary = "Exchange credentials for an access token")
    public AuthService.AuthResponse login(@Valid @RequestBody AuthDtos.LoginRequest request) {
        return auth.login(request.username(), request.password());
    }

    @PostMapping("/refresh")
    @Operation(summary = "Issue a new access token for the authenticated user")
    public AuthService.AuthResponse refresh() {
        return auth.refreshCurrentUser();
    }

    @GetMapping("/me")
    @Operation(summary = "Profile of the authenticated user")
    public UserSummary me() {
        return currentUser.requireCurrentUserSummary();
    }
}
