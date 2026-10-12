package org.polyzon;

import java.util.Locale;

/** Order of search results. */
public enum Sort {
    /** By relevance to the query (the server default). */
    RELEVANT,
    /** By popularity on the source site. */
    POPULAR,
    /** Newest first. */
    NEWEST,
    /** Most liked first. */
    LIKES,
    /** Most printed ("makes") first. */
    MAKES,
    /** Most downloaded first. */
    DOWNLOADS;

    /**
     * Returns the value sent in the {@code sort} query parameter.
     *
     * @return the lowercase name, for example {@code "newest"}
     */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
