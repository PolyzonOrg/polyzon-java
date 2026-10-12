package org.polyzon;

import java.util.List;

/**
 * One page of search results.
 *
 * @param query the query as the server understood it
 * @param correctedQuery the spelling-corrected query that was actually searched, or {@code null}
 * @param didYouMean a suggested alternative query, or {@code null}
 * @param total the number of matching models (see {@code totalRelation})
 * @param totalRelation {@link #RELATION_EQ} when {@code total} is exact, {@link #RELATION_GTE}
 *     when it is a lower bound
 * @param page the page number, from 1
 * @param size the page size
 * @param hasMore whether a next page exists within the 2400-model window
 * @param partial whether part of the catalogue did not answer and the results are incomplete
 * @param items the models on this page; unmodifiable
 * @param meta request id and daily quota; excluded from {@code equals}, {@code hashCode} and
 *     {@code toString}
 */
public record SearchPage(
        String query,
        String correctedQuery,
        String didYouMean,
        long total,
        String totalRelation,
        long page,
        long size,
        boolean hasMore,
        boolean partial,
        List<SearchItem> items,
        ResponseMeta meta) {

    /** {@code total} is exact. */
    public static final String RELATION_EQ = "eq";
    /** {@code total} is a lower bound. */
    public static final String RELATION_GTE = "gte";

    /**
     * Creates a page; the list is copied.
     *
     * @param query the query
     * @param correctedQuery the corrected query, or {@code null}
     * @param didYouMean the suggested query, or {@code null}
     * @param total the number of matches
     * @param totalRelation the relation of {@code total}
     * @param page the page number
     * @param size the page size
     * @param hasMore whether a next page exists
     * @param partial whether the results are incomplete
     * @param items the models
     * @param meta the metadata
     */
    public SearchPage {
        items = List.copyOf(items);
    }

    @Override
    public boolean equals(Object other) {
        return Records.equalsIgnoringMeta(this, other);
    }

    @Override
    public int hashCode() {
        return Records.hashIgnoringMeta(this);
    }

    @Override
    public String toString() {
        return Records.toStringIgnoringMeta(this);
    }
}
