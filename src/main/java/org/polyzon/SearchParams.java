package org.polyzon;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Parameters of {@link PolyzonClient#search(SearchParams)}. Build them with
 * {@link #builder(String)}:
 *
 * <pre>{@code
 * SearchParams params = SearchParams.builder("дракон")
 *         .size(50)
 *         .sort(Sort.NEWEST)
 *         .free(true)
 *         .sources(Sources.THINGIVERSE, Sources.PRINTABLES)
 *         .build();
 * }</pre>
 *
 * <p>Unset parameters are not sent, so the server defaults apply (page 1, 20 per page, sort by
 * relevance, Russian). The server enforces the upper limits (query of at most 200 characters,
 * page 1..100, size 1..50, page &times; size &le; 2400) and answers {@code 400} beyond them;
 * note that a rejected request still counts against the daily quota.
 *
 * @param query the search text in Russian or English; not blank
 * @param page the page number from 1, or {@code null} for the server default
 * @param size models per page from 1, or {@code null} for the server default
 * @param sort the order, or {@code null} for relevance
 * @param free whether to return only free models
 * @param sources source site ids (see {@link Sources}); empty for all
 * @param formats file formats such as {@code stl} or {@code 3mf}; empty for all
 * @param licenses license ids (see {@link Licenses}); empty for all
 * @param lang the language of titles and tags, or {@code null} for the server default
 */
public record SearchParams(
        String query,
        Integer page,
        Integer size,
        Sort sort,
        boolean free,
        List<String> sources,
        List<String> formats,
        List<String> licenses,
        Lang lang) {

    /**
     * Creates and validates parameters; the lists are copied.
     *
     * @param query the search text
     * @param page the page number, or {@code null}
     * @param size models per page, or {@code null}
     * @param sort the order, or {@code null}
     * @param free only free models
     * @param sources source site ids
     * @param formats file formats
     * @param licenses license ids
     * @param lang the language, or {@code null}
     * @throws NullPointerException if {@code query}, a list or a list element is null
     * @throws IllegalArgumentException if {@code query} is blank, {@code page} or {@code size}
     *     is below 1, or a list element is blank or contains a comma
     */
    public SearchParams {
        Objects.requireNonNull(query, "query");
        if (query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (page != null && page < 1) {
            throw new IllegalArgumentException("page must be at least 1");
        }
        if (size != null && size < 1) {
            throw new IllegalArgumentException("size must be at least 1");
        }
        sources = csvValues("sources", sources);
        formats = csvValues("formats", formats);
        licenses = csvValues("licenses", licenses);
    }

    /**
     * Starts building parameters for a query.
     *
     * @param query the search text in Russian or English
     * @return a new builder
     */
    public static Builder builder(String query) {
        return new Builder(query);
    }

    /**
     * Returns a builder initialised with these parameters.
     *
     * @return a new builder
     */
    public Builder toBuilder() {
        Builder builder = new Builder(query);
        builder.page = page;
        builder.size = size;
        builder.sort = sort;
        builder.free = free;
        builder.sources = sources;
        builder.formats = formats;
        builder.licenses = licenses;
        builder.lang = lang;
        return builder;
    }

    private static List<String> csvValues(String name, List<String> values) {
        List<String> copy = List.copyOf(Objects.requireNonNull(values, name));
        for (String value : copy) {
            if (value.isBlank() || value.indexOf(',') >= 0) {
                throw new IllegalArgumentException(name + " values must be non-blank and contain no comma");
            }
        }
        return copy;
    }

    /** Mutable builder of {@link SearchParams}; not thread-safe. */
    public static final class Builder {
        private final String query;
        private Integer page;
        private Integer size;
        private Sort sort;
        private boolean free;
        private List<String> sources = List.of();
        private List<String> formats = List.of();
        private List<String> licenses = List.of();
        private Lang lang;

        private Builder(String query) {
            this.query = query;
        }

        /**
         * Sets the page number (1..100 on the server).
         *
         * @param page the page number from 1
         * @return this builder
         */
        public Builder page(int page) {
            this.page = page;
            return this;
        }

        /**
         * Sets the page size (1..50 on the server, 20 by default).
         *
         * @param size models per page
         * @return this builder
         */
        public Builder size(int size) {
            this.size = size;
            return this;
        }

        /**
         * Sets the order of results.
         *
         * @param sort the order, or {@code null} for the server default (relevance)
         * @return this builder
         */
        public Builder sort(Sort sort) {
            this.sort = sort;
            return this;
        }

        /**
         * Restricts results to free models ({@code free=1}). {@code false} sends nothing.
         *
         * @param free whether to return only free models
         * @return this builder
         */
        public Builder free(boolean free) {
            this.free = free;
            return this;
        }

        /**
         * Restricts results to source sites (see {@link Sources}; any id is accepted).
         *
         * @param sources source site ids; none for all sites
         * @return this builder
         */
        public Builder sources(String... sources) {
            return sources(Arrays.asList(sources));
        }

        /**
         * Restricts results to source sites (see {@link Sources}; any id is accepted).
         *
         * @param sources source site ids; empty for all sites
         * @return this builder
         */
        public Builder sources(Collection<String> sources) {
            this.sources = List.copyOf(sources);
            return this;
        }

        /**
         * Restricts results to file formats such as {@code stl}, {@code 3mf}, {@code obj},
         * {@code step}.
         *
         * @param formats file formats; none for all
         * @return this builder
         */
        public Builder formats(String... formats) {
            return formats(Arrays.asList(formats));
        }

        /**
         * Restricts results to file formats.
         *
         * @param formats file formats; empty for all
         * @return this builder
         */
        public Builder formats(Collection<String> formats) {
            this.formats = List.copyOf(formats);
            return this;
        }

        /**
         * Restricts results to licenses (see {@link Licenses}; sent as the {@code license}
         * parameter).
         *
         * @param licenses license ids; none for all
         * @return this builder
         */
        public Builder licenses(String... licenses) {
            return licenses(Arrays.asList(licenses));
        }

        /**
         * Restricts results to licenses (see {@link Licenses}).
         *
         * @param licenses license ids; empty for all
         * @return this builder
         */
        public Builder licenses(Collection<String> licenses) {
            this.licenses = List.copyOf(licenses);
            return this;
        }

        /**
         * Sets the language of titles and tags.
         *
         * @param lang the language, or {@code null} for the server default (Russian)
         * @return this builder
         */
        public Builder lang(Lang lang) {
            this.lang = lang;
            return this;
        }

        /**
         * Validates and builds the parameters.
         *
         * @return the parameters
         * @throws NullPointerException if the query is null
         * @throws IllegalArgumentException if a value is invalid (see {@link SearchParams})
         */
        public SearchParams build() {
            return new SearchParams(query, page, size, sort, free, sources, formats, licenses, lang);
        }
    }
}
