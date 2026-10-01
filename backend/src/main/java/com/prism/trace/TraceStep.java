package com.prism.trace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One observable step inside a {@link TraceRun}.
 *
 * <p>Deliberately records <em>observable</em> execution only: the prompt sent,
 * the evidence retrieved, the deterministic rule output, the model's returned
 * fields, fusion arithmetic, and human overrides. It does not store hidden
 * chain-of-thought, and it must never store credentials.
 */
@Entity
@Table(name = "trace_steps")
public class TraceStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private TraceRun run;

    /** Null for a root step; otherwise the enclosing step, forming the replay tree. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_step_id")
    private TraceStep parentStep;

    /**
     * Ordering within the run. Allocated by the database so concurrent writers
     * never collide on a client-computed sequence number.
     */
    @Column(name = "seq", nullable = false)
    private Long seq;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 16)
    private ActorType actorType;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private TraceEventType eventType;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "status", nullable = false, length = 24)
    private String status = "OK";

    @Column(name = "input_summary", length = 1000)
    private String inputSummary;

    /** JSON array of the referenced object ids, e.g. {@code [12,13]}. */
    @Lob
    @Column(name = "input_reference_ids", columnDefinition = "TEXT")
    private String inputReferenceIds;

    @Column(name = "output_summary", length = 2000)
    private String outputSummary;

    @Lob
    @Column(name = "output_reference_ids", columnDefinition = "TEXT")
    private String outputReferenceIds;

    @Column(name = "rule_version", length = 64)
    private String ruleVersion;

    @Column(name = "prompt_version", length = 64)
    private String promptVersion;

    @Column(name = "model", length = 200)
    private String model;

    @Column(name = "duration_ms")
    private Long durationMs;

    /** Failure detail. Never contains credentials or raw auth headers. */
    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected TraceStep() {
        // for JPA
    }

    public TraceStep(TraceRun run, TraceStep parentStep, ActorType actorType, TraceEventType eventType,
                     String name, String status) {
        this.run = run;
        this.parentStep = parentStep;
        this.actorType = actorType;
        this.eventType = eventType;
        this.name = name;
        this.status = status;
        this.createdAt = Instant.now();
    }

    public void assignSeq(Long seq) {
        this.seq = seq;
    }

    public Long getId() {
        return id;
    }

    public TraceRun getRun() {
        return run;
    }

    public TraceStep getParentStep() {
        return parentStep;
    }

    public Long getSeq() {
        return seq;
    }

    public ActorType getActorType() {
        return actorType;
    }

    public TraceEventType getEventType() {
        return eventType;
    }

    public String getName() {
        return name;
    }

    public String getStatus() {
        return status;
    }

    public String getInputSummary() {
        return inputSummary;
    }

    public void setInputSummary(String inputSummary) {
        this.inputSummary = inputSummary;
    }

    public String getInputReferenceIds() {
        return inputReferenceIds;
    }

    public void setInputReferenceIds(String inputReferenceIds) {
        this.inputReferenceIds = inputReferenceIds;
    }

    public String getOutputSummary() {
        return outputSummary;
    }

    public void setOutputSummary(String outputSummary) {
        this.outputSummary = outputSummary;
    }

    public String getOutputReferenceIds() {
        return outputReferenceIds;
    }

    public void setOutputReferenceIds(String outputReferenceIds) {
        this.outputReferenceIds = outputReferenceIds;
    }

    public String getRuleVersion() {
        return ruleVersion;
    }

    public void setRuleVersion(String ruleVersion) {
        this.ruleVersion = ruleVersion;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public int getAttempt() {
        return attempt;
    }

    public void setAttempt(int attempt) {
        this.attempt = attempt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
