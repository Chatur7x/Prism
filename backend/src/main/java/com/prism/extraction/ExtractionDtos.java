package com.prism.extraction;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Strict DTOs for extraction output.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = false)} is the default under
 * {@code FAIL_ON_UNKNOWN_PROPERTIES}, so a model that invents extra keys fails
 * validation instead of silently losing data. That is intentional.
 */
public final class ExtractionDtos {

    private ExtractionDtos() {
    }

    /**
     * Valid predicate shape: lowercase snake_case, 3-60 chars.
     * Anchored at both ends so a partially matching string cannot pass.
     */
    public static final String PREDICATE_PATTERN = "^[a-z][a-z_]{2,59}$";

    public record ExtractionResultDto(
            @NotNull(message = "triples must be present, use [] when empty")
            @Valid
            List<TripleDto> triples,

            @NotNull(message = "claims must be present, use [] when empty")
            @Valid
            List<ClaimDto> claims) {

        public ExtractionResultDto {
            // Jackson can hand us null for a missing array; normalise so
            // validation reports "absent" rather than throwing NPE downstream.
            triples = triples == null ? List.of() : List.copyOf(triples);
            claims = claims == null ? List.of() : List.copyOf(claims);
        }

        public static ExtractionResultDto empty() {
            return new ExtractionResultDto(List.of(), List.of());
        }
    }

    public record TripleDto(
            @NotBlank(message = "subject is required")
            @Size(max = 300, message = "subject must be at most 300 characters")
            String subject,

            @NotBlank(message = "predicate is required")
            @Pattern(regexp = PREDICATE_PATTERN,
                    message = "predicate must be lowercase snake_case matching ^[a-z][a-z_]{2,59}$")
            String predicate,

            @NotBlank(message = "object is required")
            @Size(max = 300, message = "object must be at most 300 characters")
            String object,

            @NotBlank(message = "sentence is required and must be quoted from the source")
            @Size(max = 600, message = "sentence must be at most 600 characters")
            String sentence) {
    }

    public record ClaimDto(
            @NotBlank(message = "subject is required")
            @Size(max = 300, message = "subject must be at most 300 characters")
            String subject,

            @NotBlank(message = "claim is required")
            @Size(max = 600, message = "claim must be at most 600 characters")
            String claim,

            @NotNull(message = "polarity is required")
            Polarity polarity,

            @NotBlank(message = "sentence is required and must be quoted from the source")
            @Size(max = 600, message = "sentence must be at most 600 characters")
            String sentence) {
    }

    /**
     * Polarity is a closed set. Anything else is a validation failure, not a
     * default.
     */
    public enum Polarity {
        POSITIVE,
        NEGATIVE,
        NEUTRAL
    }
}
