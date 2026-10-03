package com.prism.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LIKE wildcards in a chat question must match literally.
 *
 * <p>A question containing {@code %} used to match every approved triple's
 * subject and object, stuffing same-corpus facts into the model prompt. The
 * blast radius was bounded (corpus-scoped, APPROVED-only, citation-checked),
 * but over-inclusion in a prompt is still a defect. These pin the escaping;
 * the {@code ESCAPE '\'} clause on the query it feeds is what makes the
 * backslashes mean anything.
 */
class ChatLikeEscapeTest {

    @Test
    @DisplayName("percent, underscore and backslash match literally")
    void metacharactersAreEscaped() {
        assertThat(ChatService.escapeLike("100% sure")).isEqualTo("100\\% sure");
        assertThat(ChatService.escapeLike("a_b")).isEqualTo("a\\_b");
        assertThat(ChatService.escapeLike("back\\slash")).isEqualTo("back\\\\slash");
    }

    @Test
    @DisplayName("ordinary text passes through untouched")
    void plainTextUntouched() {
        assertThat(ChatService.escapeLike("meridian group reports")).isEqualTo("meridian group reports");
        assertThat(ChatService.escapeLike("")).isEqualTo("");
    }

    @Test
    @DisplayName("escaping order matters: backslash first")
    void backslashEscapedFirst() {
        // If % were escaped before \, the escape backslash itself would be
        // escaped a second time and match a literal backslash plus anything.
        assertThat(ChatService.escapeLike("%\\")).isEqualTo("\\%\\\\");
    }
}
