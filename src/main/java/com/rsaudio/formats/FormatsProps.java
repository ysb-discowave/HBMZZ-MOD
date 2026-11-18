package com.rsaudio.formats;

/**
 * System-property lookup for the merged build.
 *
 * <p>The properties used to be spelled {@code -Drsaudiofm.*} while the formats support was a
 * standalone addon, and the external verification scripts still pass that spelling. Both names are
 * therefore accepted, the new {@code rsaudio-formats.*} one first: a rename must never silently
 * disable a switch somebody's launcher already sets.</p>
 */
public final class FormatsProps {

    /** Current spelling, without the trailing dot. */
    public static final String PREFIX = "rsaudio-formats.";
    /** Spelling this feature shipped with; kept so existing launch scripts keep working. */
    public static final String LEGACY_PREFIX = "rsaudiofm.";

    private FormatsProps() {
    }

    /** Raw value of {@code rsaudio-formats.<name>}, falling back to {@code rsaudiofm.<name>}. */
    public static String get(String name) {
        try {
            String value = System.getProperty(PREFIX + name);
            if (value != null) {
                return value;
            }
            return System.getProperty(LEGACY_PREFIX + name);
        } catch (Throwable t) {
            return null;
        }
    }

    /** True when either spelling is present, whatever its value. */
    public static boolean present(String name) {
        return get(name) != null;
    }

    /** Integer value of the property, or {@code fallback} when absent or unparsable. */
    public static int getInt(String name, int fallback) {
        String raw = get(name);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
