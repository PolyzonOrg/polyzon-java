package org.polyzon;

import java.util.List;

/**
 * A model card.
 *
 * @param source the source site id
 * @param id the model id with the Polyzon prefix
 * @param title the title in the requested language
 * @param url the model page on the source site
 * @param description the description in the requested language, or {@code null}
 * @param thumbnail the preview image URL, or {@code null}
 * @param images image URLs on the source site's CDN (at most 20); unmodifiable
 * @param creator the author's name, or {@code null}
 * @param creatorUrl the author's page, or {@code null}
 * @param category the category on the source site, or {@code null}
 * @param license the license, or {@code null} when unknown
 * @param price the price, or {@code null} when unknown
 * @param formats file formats; unmodifiable
 * @param tags tags in the requested language; unmodifiable
 * @param stats counters from the source site
 * @param publishedAt the publication time as an ISO-8601 string, or {@code null}
 * @param updatedAt the last update as an ISO-8601 string, or {@code null}
 * @param copies the same model on other sites; unmodifiable
 * @param meta request id and daily quota; excluded from {@code equals}, {@code hashCode} and
 *     {@code toString}
 */
public record Model(
        String source,
        String id,
        String title,
        String url,
        String description,
        String thumbnail,
        List<String> images,
        String creator,
        String creatorUrl,
        String category,
        License license,
        Price price,
        List<String> formats,
        List<String> tags,
        Stats stats,
        String publishedAt,
        String updatedAt,
        List<ModelRef> copies,
        ResponseMeta meta) {

    /**
     * Creates a model; the lists are copied.
     *
     * @param source the source site id
     * @param id the model id
     * @param title the title
     * @param url the model page
     * @param description the description, or {@code null}
     * @param thumbnail the preview image URL, or {@code null}
     * @param images image URLs
     * @param creator the author's name, or {@code null}
     * @param creatorUrl the author's page, or {@code null}
     * @param category the category, or {@code null}
     * @param license the license, or {@code null}
     * @param price the price, or {@code null}
     * @param formats file formats
     * @param tags tags
     * @param stats counters
     * @param publishedAt the publication time, or {@code null}
     * @param updatedAt the last update, or {@code null}
     * @param copies the same model on other sites
     * @param meta the metadata
     */
    public Model {
        images = List.copyOf(images);
        formats = List.copyOf(formats);
        tags = List.copyOf(tags);
        copies = List.copyOf(copies);
    }

    /**
     * Returns the reference to this model.
     *
     * @return the reference
     */
    public ModelRef ref() {
        return new ModelRef(source, id);
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

    /**
     * The model's license.
     *
     * @param id the license id (see {@link Licenses})
     * @param raw the license text as the source site states it, or {@code null}
     */
    public record License(String id, String raw) {
    }

    /**
     * Counters from the source site; {@code null} when the site does not report one.
     *
     * @param likes likes
     * @param comments comments
     * @param makes prints shared by users
     * @param collects saves to collections
     * @param downloads downloads
     * @param views views
     */
    public record Stats(Long likes, Long comments, Long makes, Long collects, Long downloads, Long views) {
    }
}
