package org.polyzon;

import java.util.Locale;

/** Language of titles, descriptions and tags; other languages are machine-translated. */
public enum Lang {
    /** Russian (the server default). */
    RU,
    /** English. */
    EN;

    /**
     * Returns the value sent in the {@code lang} query parameter.
     *
     * @return the lowercase name, for example {@code "en"}
     */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
