package org.polyzon;

import java.util.Objects;

/**
 * Outcome of one reference in {@link PolyzonClient#getModels(java.util.List, int)}: exactly one
 * of {@code model} and {@code error} is non-null.
 *
 * @param ref the requested reference
 * @param model the model, or {@code null} on failure
 * @param error why the model could not be fetched, or {@code null} on success: a
 *     {@link org.polyzon.errors.PolyzonException}, or an {@link IllegalArgumentException} for an
 *     invalid reference
 */
public record ModelResult(ModelRef ref, Model model, RuntimeException error) {
    /**
     * Creates a result.
     *
     * @param ref the requested reference
     * @param model the model, or {@code null}
     * @param error the failure, or {@code null}
     * @throws NullPointerException if {@code ref} is null
     * @throws IllegalArgumentException unless exactly one of {@code model} and {@code error} is set
     */
    public ModelResult {
        Objects.requireNonNull(ref, "ref");
        if ((model == null) == (error == null)) {
            throw new IllegalArgumentException("exactly one of model and error must be set");
        }
    }

    /**
     * Tells whether the model was fetched.
     *
     * @return {@code true} when {@link #model()} is set
     */
    public boolean isSuccess() {
        return model != null;
    }

    /**
     * Returns the model or throws the failure.
     *
     * @return the model
     * @throws RuntimeException the {@link #error()} when the fetch failed
     */
    public Model orElseThrow() {
        if (error != null) {
            throw error;
        }
        return model;
    }
}
