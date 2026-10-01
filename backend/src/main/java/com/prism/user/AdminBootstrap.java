package com.prism.user;

import com.prism.config.SecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first ADMIN account when the database has no users, so a fresh
 * deployment is administrable. Runs exactly once, guarded by the user count.
 *
 * <p>The bootstrap password comes from configuration. If it is left at the
 * default, a warning is logged loudly rather than silently accepted.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    private static final String DEFAULT_PASSWORD = "ChangeMe!123";

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final SecurityProperties props;

    public AdminBootstrap(UserRepository users, PasswordEncoder encoder, SecurityProperties props) {
        this.users = users;
        this.encoder = encoder;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        String username = props.bootstrapAdminUsername();
        String email = props.bootstrapAdminEmail();
        String password = props.bootstrapAdminPassword();

        users.save(new User(username, email, encoder.encode(password), Role.ADMIN));
        log.info("Bootstrapped initial ADMIN account '{}'. Set BOOTSTRAP_ADMIN_PASSWORD before any real deployment.",
                username);

        if (DEFAULT_PASSWORD.equals(password)) {
            log.warn("SECURITY: BOOTSTRAP_ADMIN_PASSWORD is still the built-in default. "
                    + "Change it immediately and rotate the account password.");
        }
    }
}
