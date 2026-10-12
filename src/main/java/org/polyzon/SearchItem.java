package org.polyzon;

import java.util.List;

/**
 * One model in a {@link SearchPage}.
 *
 * @param source the source site id, for example {@link Sources#THINGIVERSE}
 * @param id the model id with the Polyzon prefix, for example {@code tv-1234567}
 * @param title the title in the requested language
 * @param url the model page on the source site
 * @param thumbnail the preview image URL, or {@code null}
 * @param creator the author's name, or {@code null}
 * @param license the license id (see {@link Licenses}), or {@code null}
 * @param price the price, or {@code null} when unknown
 * @param formats file formats, for example {@code stl}; unmodifiable
 * @param tags tags in the requested language; unmodifiable
 * @param stats counters from the source site
 * @param publishedAt the publication time as an ISO-8601 string, or {@code null}
 */
public record SearchItem(
        String source,
        String id,
        String title,
        String url,
        String thumbnail,
        String creator,
        String license,
        Price price,
        List<String> formats,
        List<String> tags,
        Stats stats,
        String publishedAt) {

    /**
     * Creates an item; the lists are copied.
     *
     * @param source the source site id
     * @param id the model id
     * @param title the title
     * @param url the model page
     * @param thumbnail the preview image URL, or {@code null}
     * @param creator the author's name, or {@code null}
     * @param license the license id, or {@code null}
     * @param price the price, or {@code null}
     * @param formats file formats
     * @param tags tags
     * @param stats counters
     * @param publishedAt the publication time, or {@code null}
     */
    public SearchItem {
        formats = List.copyOf(formats);
        tags = List.copyOf(tags);
    }

    /**
     * Returns the reference to this model, for {@link PolyzonClient#getModel(String, String)}
     * and {@link PolyzonClient#getModels(List, int)}.
     *
     * @return the reference
     */
    public ModelRef ref() {
        return new ModelRef(source, id);
    }

    /**
     * Counters from the source site; {@code null} when the site does not report one.
     *
     * @param likes likes
     * @param comments comments
     * @param makes prints shared by users
     * @param collects saves to collections, or downloads when the site does not count saves
     */
    public record Stats(Long likes, Long comments, Long makes, Long collects) {
    }
}
