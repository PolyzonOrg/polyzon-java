package org.polyzon;

/**
 * License ids known when this SDK was released, for {@link SearchParams.Builder#licenses} and
 * {@link SearchItem#license()}. The API may return other ids; treat them as plain strings.
 */
public final class Licenses {
    /** Public domain dedication (CC0). */
    public static final String CC0 = "cc0";
    /** Creative Commons Attribution. */
    public static final String CC_BY = "cc-by";
    /** Creative Commons Attribution-ShareAlike. */
    public static final String CC_BY_SA = "cc-by-sa";
    /** Creative Commons Attribution-NoDerivatives. */
    public static final String CC_BY_ND = "cc-by-nd";
    /** Creative Commons Attribution-NonCommercial. */
    public static final String CC_BY_NC = "cc-by-nc";
    /** Creative Commons Attribution-NonCommercial-ShareAlike. */
    public static final String CC_BY_NC_SA = "cc-by-nc-sa";
    /** Creative Commons Attribution-NonCommercial-NoDerivatives. */
    public static final String CC_BY_NC_ND = "cc-by-nc-nd";
    /** GNU General Public License. */
    public static final String GPL = "gpl";
    /** GNU Lesser General Public License. */
    public static final String LGPL = "lgpl";
    /** MIT License. */
    public static final String MIT = "mit";
    /** BSD License. */
    public static final String BSD = "bsd";
    /** Personal use only. */
    public static final String PRIVATE_USE = "private-use";
    /** Commercial use allowed by the site's own terms. */
    public static final String COMMERCIAL = "commercial";
    /** A site-specific license; see the model page. */
    public static final String CUSTOM = "custom";
    /** The license could not be determined. */
    public static final String UNKNOWN = "unknown";

    private Licenses() {
    }
}
