package com.prism.auth;

import com.prism.user.User;
import com.prism.user.UserPrincipal;
import com.prism.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads principals for the security filter. Kept separate from {@code UserService}
 * so the filter never depends on service methods that can throw API exceptions.
 */
@Service
public class UserAccountService {

    private final UserRepository users;

    public UserAccountService(UserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public UserPrincipal loadActivePrincipal(Long userId) {
        return users.findById(userId)
                .filter(User::isEnabled)
                .map(UserPrincipal::new)
                .orElse(null);
    }
}
