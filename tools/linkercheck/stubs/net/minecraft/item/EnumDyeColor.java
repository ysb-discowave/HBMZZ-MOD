package net.minecraft.item;

/**
 * Stand-in for 1.12.2's dye colours.
 *
 * <p>Only the pieces the control classes touch at construction time:
 * {@code RED} as the default button colour and {@code getColorComponentValues()}
 * for rendering. The RGB values are the real ones.</p>
 */
public enum EnumDyeColor {

    WHITE(0xFFFFFF),
    ORANGE(0xF9801D),
    MAGENTA(0xC74EBD),
    LIGHT_BLUE(0x3AB3DA),
    YELLOW(0xFED83D),
    LIME(0x80C71F),
    PINK(0xF38BAA),
    GRAY(0x474F52),
    SILVER(0x9D9D97),
    CYAN(0x169C9C),
    PURPLE(0x8932B8),
    BLUE(0x3C44AA),
    BROWN(0x835432),
    GREEN(0x5E7C16),
    RED(0xB02E26),
    BLACK(0x1D1D21);

    private final float[] components;

    EnumDyeColor(int rgb) {
        this.components = new float[]{
                ((rgb >> 16) & 0xFF) / 255F,
                ((rgb >> 8) & 0xFF) / 255F,
                (rgb & 0xFF) / 255F,
        };
    }

    public float[] getColorComponentValues() {
        return this.components;
    }
}
