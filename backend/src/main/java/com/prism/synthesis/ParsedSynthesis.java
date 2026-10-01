package com.prism.synthesis;

import java.util.List;

/**
 * The outcome of parsing and validating a synthesis response.
 *
 * <p>{@code valid} is not decoration. A model response that is well-formed JSON
 * with the right keys can still be rejected — an unknown {@code block_type}, a
 * citation pointing at an argument that was never supplied, an empty
 * conclusion. This record carries that verdict so the caller writes nothing at
 * all on a rejection rather than storing a partial report, which would be
 * indistinguishable later from a complete one.
 *
 * @param error why the response was rejected; null when valid
 */
public record ParsedSynthesis(String conclusion, List<ParsedBlock> blocks,
                              boolean valid, String error) {
}
