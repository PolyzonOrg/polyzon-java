package org.polyzon;

import java.math.BigDecimal;

/**
 * Price of a model.
 *
 * @param type {@link #FREE}, {@link #PAID} or {@link #OPEN}; other values may appear later
 * @param value the amount, or {@code null}
 * @param currency the currency code, or {@code null}
 * @param display the price as Polyzon shows it, for example {@code "Бесплатно"}; may be empty
 */
public record Price(String type, BigDecimal value, String currency, String display) {
    /** The model is free to download. */
    public static final String FREE = "free";
    /** The model is sold. */
    public static final String PAID = "paid";
    /** Pay what you want, or a free model with optional payment. */
    public static final String OPEN = "open";
}
