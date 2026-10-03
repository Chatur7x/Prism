package com.prism.user;

import com.prism.common.PageResponse;
import com.prism.config.LlmProperties;
import com.prism.common.PageResponse;
import com.prism.corpus.CorpusAccessService;
import com.prism.common.PageResponse;
import com.prism.llm.LlmClient;
import com.prism.common.PageResponse;
import com.prism.pipeline.BackgroundJob;
import com.prism.common.PageResponse;
import com.prism.pipeline.BackgroundJobRepository;
import com.prism.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import com.prism.common.PageResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.prism.common.PageResponse;
import jakarta.validation.Valid;
import com.prism.common.PageResponse;
import jakarta.validation.constraints.NotNull;
import com.prism.common.PageResponse;
import org.springframework.data.domain.PageRequest;
import com.prism.common.PageResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import com.prism.common.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import com.prism.common.PageResponse;
import org.springframework.web.bind.annotation.PatchMapping;
import com.prism.common.PageResponse;
import org.springframework.web.bind.annotation.PathVariable;
import com.prism.common.PageResponse;
import org.springframework.web.bind.annotation.PostMapping;
import com.prism.common.PageResponse;
import org.springframework.web.bind.annotation.RequestBody;
import com.prism.common.PageResponse;
import org.springframework.web.bind.annotation.RequestMapping;
import com.prism.common.PageResponse;
import org.springframework.web.bind.annotation.RequestParam;
import com.prism.common.PageResponse;
import org.springframework.web.bind.annotation.RestController;

import com.prism.common.PageResponse;
import java.time.Instant;
import com.prism.common.PageResponse;
import java.util.LinkedHashMap;
import com.prism.common.PageResponse;
import java.util.List;
import com.prism.common.PageResponse;
import java.util.Map;

/** Administration. Every route requires the ADMIN role. */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Administration", description = "User management, health, and system state")
public class AdminController {

    private final UserService users;
    private final CorpusAccessService access;
    private final BackgroundJobRepository jobs;
    private final LlmClient llm;
    private final LlmProperties llmProperties;

    public AdminController(UserService users, CorpusAccessService access,
                           BackgroundJobRepository jobs, LlmClient llm, LlmProperties llmProperties) {
        this.users = users;
        this.access = access;
        this.jobs = jobs;
        this.llm = llm;
        this.llmProperties = llmProperties;
    }

    public record UpdateUserRequest(@NotNull Role role, Boolean enabled) {
    }

    public record CreateUserRequest(
            @NotNull String username,
            @NotNull String email,
            @NotNull String password,
            @NotNull Role role) {
    }

    @GetMapping("/users")
    @Operation(summary = "List all accounts",
            description = "Returns the standard page envelope. This used to hand-build a map with a "
                    + "different key set, which the frontend typed as a bare array -- so the admin "
                    + "page read .length off an object and always rendered its empty state.")
    public PageResponse<UserSummary> listUsers(@RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "50") int size) {
        var result = users.list(PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200)));
        return PageResponse.of(result.map(UserSummary::from));
    }

    @PostMapping("/users")
    @Operation(summary = "Create an account with an explicit role",
            description = "The only way to grant VERIFIER or ADMIN. Self-registration always "
                    + "produces an ANALYST.")
    public UserSummary createUser(@Valid @RequestBody CreateUserRequest request) {
        return UserSummary.from(users.register(request.username(), request.email(),
                request.password(), request.role()));
    }

    @PatchMapping("/users/{id}")
    @Operation(summary = "Change a role or enable/disable an account")
    public UserSummary updateUser(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest request) {
        access.requireCurrentUserId();
        User updated = users.updateRole(id, request.role());
        if (request.enabled() != null) {
            updated = users.setEnabled(id, request.enabled());
        }
        return UserSummary.from(updated);
    }

    @GetMapping("/system/status")
    @Operation(summary = "System state: LLM provider, job counts, and configuration sanity")
    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("llmProvider", llm.providerName());
        body.put("llm", llm.describe());
        body.put("offlineTestMode", "fake".equals(llmProperties.provider()));
        body.put("userCount", users.count());
        body.put("pendingJobs", jobs.countByStatus(BackgroundJob.Status.PENDING));
        body.put("runningJobs", jobs.countByStatus(BackgroundJob.Status.RUNNING));
        body.put("failedJobs", jobs.countByStatus(BackgroundJob.Status.FAILED));
        body.put("abandonedJobs", jobs.countByStatus(BackgroundJob.Status.ABANDONED));
        body.put("checkedAt", Instant.now());
        if ("fake".equals(llmProperties.provider())) {
            body.put("warning", "LLM_PROVIDER=fake: responses are deterministic fixtures, "
                    + "not a real model. Do not use this configuration for a real analysis.");
        }
        return body;
    }

    @GetMapping("/jobs")
    @Operation(summary = "Background job queue, for operational inspection")
    public List<Map<String, Object>> jobs(@RequestParam(required = false) BackgroundJob.Status status) {
        var list = status == null ? jobs.findAll() : jobs.findByStatus(status);
        return list.stream().map(j -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", j.getId());
            row.put("jobKey", j.getJobKey());
            row.put("type", j.getJobType().name());
            row.put("status", j.getStatus().name());
            row.put("attempts", j.getAttemptCount() + "/" + j.getMaxAttempts());
            row.put("corpusId", j.getCorpus() == null ? null : j.getCorpus().getId());
            row.put("documentId", j.getDocument() == null ? null : j.getDocument().getId());
            row.put("heartbeatAt", j.getHeartbeatAt());
            row.put("lastError", j.getLastError());
            return row;
        }).toList();
    }
}
