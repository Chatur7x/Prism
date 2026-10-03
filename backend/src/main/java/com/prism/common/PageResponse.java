package com.prism.common;

import java.util.List;

/**
 * The one collection envelope every paged endpoint returns.
 *
 * <p>There was no single shape before this. {@code GET /api/documents} returned a
 * bare array and discarded the total, {@code GET /api/admin/users} hand-built a
 * map with a different set of keys, and the quarantine endpoint used
 * {@code {total, content}}. Three shapes for one concept, and the frontend typed
 * all three differently -- which is how the admin page came to read
 * {@code users.data.length} against a response that has no {@code length} at all,
 * so it always rendered the empty state and never showed a single account.
 *
 * <p>Spring Data's own {@code Page} is deliberately not exposed. Its JSON
 * serialisation is unstable across versions and carries an internal
 * {@code pageable} object, so a client binding to it is coupled to Spring.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {

    public static <T> PageResponse<T> of(org.springframework.data.domain.Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext());
    }

    /**
     * Wraps content fetched without a {@code Page}, for queries that take a count
     * separately.
     *
     * <p>{@code totalPages} and {@code hasNext} are derived here rather than at
     * each call site, because those are exactly the two fields a hand-rolled
     * envelope gets wrong: with a truncated final page there is no way to tell
     * "this is the last page" from "there are more" without the total.
     */
    public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
        int safeSize = Math.max(size, 1);
        int safePage = Math.max(page, 0);
        int totalPages = (int) Math.ceil((double) totalElements / safeSize);
        return new PageResponse<>(content, safePage, safeSize, totalElements, totalPages,
                safePage + 1 < totalPages);
    }

    /** Single-page convenience for a list that is not paged at all. */
    public static <T> PageResponse<T> ofAll(List<T> content) {
        return of(content, 0, Math.max(content.size(), 1), content.size());
    }
}
