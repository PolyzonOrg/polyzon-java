package org.polyzon;

/**
 * The account and token that made the request ({@code GET /v1/me}).
 *
 * @param account the account that owns the token
 * @param token the token used for the request
 * @param meta request id; excluded from {@code equals}, {@code hashCode} and {@code toString}
 */
public record Me(Account account, Token token, ResponseMeta meta) {
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

    /**
     * A Polyzon account.
     *
     * @param id the account id
     * @param displayName the display name, or {@code null}
     * @param createdAt when the account was created, ISO-8601
     */
    public record Account(String id, String displayName, String createdAt) {
    }

    /**
     * An API token. The secret itself is never returned.
     *
     * @param id the token id
     * @param name the name given to the token
     * @param prefix the first characters of the token, for example {@code pzk_Q7xR2mVb}
     * @param createdAt when the token was created, ISO-8601
     */
    public record Token(String id, String name, String prefix, String createdAt) {
    }
}
