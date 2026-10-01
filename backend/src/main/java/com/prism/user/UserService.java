package com.prism.user;

import com.prism.common.error.ApiException;
import com.prism.common.error.ErrorCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * User lifecycle. Self-registration is deliberately limited to ANALYST:
 * granting VERIFIER or ADMIN at signup would let anyone approve their own
 * extracted facts, which breaks the human-authority invariant.
 */
@Service
public class UserService {

    private static final Pattern USERNAME = Pattern.compile("^[a-zA-Z0-9_.-]{3,64}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s.]{1,63}(\\.[^@\\s.]{1,63})+$");

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public User register(String username, String email, String rawPassword, Role requestedRole) {
        String u = username == null ? "" : username.trim();
        String e = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);

        if (!USERNAME.matcher(u).matches()) {
            throw ApiException.validation("username must be 3-64 characters of letters, digits, '_', '.', or '-'");
        }
        if (!EMAIL.matcher(e).matches()) {
            throw ApiException.validation("email is not a valid address");
        }
        validatePasswordStrength(rawPassword);

        if (users.existsByUsernameIgnoreCase(u)) {
            throw ApiException.conflict("username is already taken");
        }
        if (users.existsByEmailIgnoreCase(e)) {
            throw ApiException.conflict("email is already registered");
        }

        // Fail closed: privileged roles are only assignable by an admin or the bootstrap path.
        Role role = requestedRole == Role.ANALYST ? Role.ANALYST : Role.ANALYST;

        User saved = users.save(new User(u, e, passwordEncoder.encode(rawPassword), role));
        return saved;
    }

    @Transactional(readOnly = true)
    public User loadForAuthentication(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "Invalid username or password");
        }
        String trimmed = identifier.trim();
        return users.findByUsernameIgnoreCase(trimmed)
                .or(() -> users.findByEmailIgnoreCase(trimmed.toLowerCase(Locale.ROOT)))
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_CREDENTIALS, "Invalid username or password"));
    }

    @Transactional(readOnly = true)
    public User getById(Long id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("User", id));
    }

    @Transactional(readOnly = true)
    public Page<User> list(Pageable pageable) {
        return users.findAll(pageable);
    }

    @Transactional
    public User updateRole(Long id, Role role) {
        if (role == null) {
            throw ApiException.validation("role is required");
        }
        User user = getById(id);
        user.changeRole(role, Instant.now());
        return users.save(user);
    }

    @Transactional
    public User setEnabled(Long id, boolean enabled) {
        User user = getById(id);
        user.setEnabled(enabled, Instant.now());
        return users.save(user);
    }

    @Transactional
    public void changeOwnPassword(Long id, String currentPassword, String newPassword) {
        User user = getById(id);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "Current password is incorrect");
        }
        validatePasswordStrength(newPassword);
        user.changePasswordHash(passwordEncoder.encode(newPassword), Instant.now());
        users.save(user);
    }

    @Transactional(readOnly = true)
    public boolean isAdmin(Long userId) {
        return users.findById(userId).map(u -> u.getRole() == Role.ADMIN).orElse(false);
    }

    @Transactional(readOnly = true)
    public long count() {
        return users.count();
    }

    private void validatePasswordStrength(String raw) {
        if (raw == null || raw.length() < 12 || raw.length() > 128) {
            throw ApiException.validation("password must be between 12 and 128 characters");
        }
        boolean hasLetter = raw.chars().anyMatch(Character::isLetter);
        boolean hasDigit = raw.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw ApiException.validation("password must contain both letters and digits");
        }
    }
}
