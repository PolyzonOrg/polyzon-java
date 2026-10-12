/**
 * Official Java client for the Polyzon public API ({@code https://api.polyzon.org}).
 *
 * <p>Start with {@link org.polyzon.PolyzonClient}. Errors live in {@link org.polyzon.errors}.
 * The module has no dependencies outside the JDK.
 */
module org.polyzon {
    requires transitive java.net.http;

    exports org.polyzon;
    exports org.polyzon.errors;
}
