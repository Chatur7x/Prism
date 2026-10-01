package com.prism.synthesis;

/**
 * One validated block of the synthesis report.
 *
 * <p>Top level rather than nested in {@code SynthesisService} because persistence
 * needs it, and nesting it made the parsed output un-passable to a transactional
 * bean without leaking the whole service.
 *
 * <p>The ids in this record have already been validated against what the model
 * was actually shown. Parsing rejects the whole response if any citation is
 * invented, so by the time a block exists here every id in it resolves. The
 * persistence side still re-checks against the allowed set — validation at parse
 * time and enforcement at write time are separate guarantees, and only the second
 * one is load-bearing for provenance.
 */
public record ParsedBlock(BlockType type, String text,
                          java.util.List<Long> argumentIds,
                          java.util.List<String> factIds) {
}
