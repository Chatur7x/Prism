package com.prism.corpus;

import com.prism.user.Role;
import com.prism.user.User;
import com.prism.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The corpora endpoints map {@link Corpus} to DTOs in the controller, outside
 * any Hibernate session (open-in-view is off). If the owner association is
 * still a lazy proxy at that point, every list/get call dies with
 * {@code LazyInitializationException} and the whole UI corpus picker 500s.
 *
 * <p>These tests call the access boundary exactly the way the controller does
 * — service transaction commits, then the owner is touched with no session
 * open — so they fail without the {@code join fetch} and pass with it.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class CorpusOwnerFetchTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("prism_test")
            .withUsername("prism")
            .withPassword("prism")
            .withCommand("--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_unicode_ci",
                    "--default-authentication-plugin=mysql_native_password");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("prism.llm.provider", () -> "fake");
    }

    @Autowired
    CorpusAccessService access;
    @Autowired
    CorpusRepository corpora;
    @Autowired
    UserRepository users;

    private User saveUser(String prefix, Role role) {
        String tag = prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
        return users.save(new User(tag, tag + "@prism.local", "hash", role));
    }

    @Test
    @DisplayName("owner list touches owner username with no session open")
    void ownerListInitializesOwner() {
        User owner = saveUser("fetch_owner", Role.VERIFIER);
        corpora.save(new Corpus("fetch-corpus", "desc", owner));

        List<Corpus> visible = access.listAccessible(owner.getId());

        assertThat(visible).hasSize(1);
        assertThat(visible.get(0).getOwner().getUsername()).startsWith("fetch_owner_");
    }

    @Test
    @DisplayName("single-corpus fetch touches owner username with no session open")
    void singleFetchInitializesOwner() {
        User owner = saveUser("single_owner", Role.VERIFIER);
        Corpus saved = corpora.save(new Corpus("single-corpus", "desc", owner));

        Corpus fetched = access.requireAccessible(saved.getId(), owner.getId());

        assertThat(fetched.getOwner().getUsername()).startsWith("single_owner_");
    }

    @Test
    @DisplayName("admin list touches owner username with no session open")
    void adminListInitializesOwner() {
        User admin = saveUser("fetch_admin", Role.ADMIN);
        User owner = saveUser("admin_view_owner", Role.VERIFIER);
        corpora.save(new Corpus("admin-view-corpus", "desc", owner));

        List<Corpus> visible = access.listAccessible(admin.getId());

        assertThat(visible).extracting(c -> c.getOwner().getUsername())
                .anyMatch(name -> name.startsWith("admin_view_owner_"));
    }
}
