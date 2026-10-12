package org.polyzon;

import java.util.Objects;

/**
 * Identifies a model: the site it comes from and its Polyzon id, as in
 * {@code polyzon.org/model/{source}/{id}}. The values are checked only when a request is made.
 *
 * @param source the source site, for example {@link Sources#THINGIVERSE}
 * @param id the model id with the Polyzon prefix, for example {@code tv-1234567}
 */
public record ModelRef(String source, String id) {
    /**
     * Creates a reference.
     *
     * @param source the source site
     * @param id the model id
     * @throws NullPointerException if an argument is null
     */
    public ModelRef {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(id, "id");
    }
}
