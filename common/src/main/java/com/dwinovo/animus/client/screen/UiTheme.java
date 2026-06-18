package com.dwinovo.animus.client.screen;

import java.util.List;

/**
 * A BlockFrame (neobrutalist) colour theme for the Animus GUI — thick borders +
 * hard offset shadows + square corners + a dot-grid ground, drawn procedurally so
 * the whole palette swaps instantly. Five presets, picked by the player in Settings.
 *
 * <p>Design rule: a FILLED background (with border + hard shadow) marks something
 * <em>clickable</em> — buttons, the input field, tabs. Status displays (tool calls,
 * plan items, messages) are plain text with a coloured glyph, never a filled pill.
 *
 * <p>All colours are ARGB. Grounds are mid-tone (never stark white); borders double
 * as the hard-shadow colour (warm-dark for the cozy themes, near-black for the MC ones).
 */
public record UiTheme(
        String id, String label,
        int ground, int dot, int band, int onBand, int border,
        int text, int textDim, int field,
        int cta, int onCta,
        int reply, int ok, int run, int fail) {

    // grounds + bands sourced from real MC palettes / cozy references (see tools/ui-textures).
    public static final UiTheme VANILLA = new UiTheme("vanilla", "Vanilla",
            0xFFC6C6C6, 0x18000000, 0xFF565656, 0xFFFFFFFF, 0xFF161616,
            0xFF16181B, 0xFF55585C, 0xFFB3B3B3,
            0xFFFFAA00, 0xFF111111,
            0xFF1C8E9C, 0xFF2E9E3A, 0xFFB07A12, 0xFFC0392B);

    public static final UiTheme FORMAT = new UiTheme("format", "Classic",
            0xFFAAAAAA, 0x1A000000, 0xFF00AAAA, 0xFF06363A, 0xFF000000,
            0xFF121212, 0xFF4A4A4A, 0xFF9A9A9A,
            0xFFFFAA00, 0xFF111111,
            0xFF128A98, 0xFF2A9E36, 0xFFA8730E, 0xFFC0392B);

    public static final UiTheme DIAMOND = new UiTheme("diamond", "Diamond",
            0xFFA9B4BC, 0x1A0B1014, 0xFF2C7E86, 0xFFEAFBFF, 0xFF13171B,
            0xFF15191D, 0xFF4C545C, 0xFF97A4AD,
            0xFFFFAA00, 0xFF111111,
            0xFF1C7C86, 0xFF2E8E3E, 0xFFA8730E, 0xFFB23A2C);

    public static final UiTheme COZY = new UiTheme("cozy", "Cozy",
            0xFFEAD3A8, 0x22332A18, 0xFF8B6D9C, 0xFFFBF5EF, 0xFF2C2540,
            0xFF2C2540, 0xFF6A6276, 0xFFDFC79A,
            0xFFE0A53A, 0xFF2C2540,
            0xFF5B7BA6, 0xFF5E8C46, 0xFFA8741E, 0xFFB05A50);

    public static final UiTheme WARM = new UiTheme("warm", "Cottage",
            0xFFCBA87B, 0x2A2A2012, 0xFF6E8F66, 0xFFF6EFD9, 0xFF352818,
            0xFF352818, 0xFF6E5E48, 0xFFBE9C70,
            0xFFE3A23A, 0xFF2A2012,
            0xFF4E7480, 0xFF577E3C, 0xFFA8731E, 0xFFA8533A);

    public static final List<UiTheme> ALL = List.of(VANILLA, FORMAT, DIAMOND, COZY, WARM);

    // Single active theme for now (Cottage/Cozy-2); the picker + persistence land later, hence the
    // full presets above are kept ready.
    private static UiTheme current = WARM;

    public static UiTheme current() { return current; }

    public static void set(String id) {
        for (UiTheme t : ALL) {
            if (t.id().equals(id)) { current = t; return; }
        }
        current = FORMAT;
    }
}
