package com.dwinovo.numen.core.task;

import net.minecraft.resources.Identifier;

import java.util.stream.Stream;

/**
 * "Did you mean…" for registry ids — the reactive half of the world_atlas
 * skill. The classic traps (woodland_mansion → mansion, ocean_monument →
 * monument, jungle_temple → jungle_pyramid) are one edit-distance lookup away
 * from self-healing instead of a dead "unknown structure".
 */
final class IdSuggest {

    private IdSuggest() {}

    /**
     * The candidate whose PATH is closest to the input's path, or null when
     * nothing is plausibly close (threshold scales with input length, plus a
     * containment shortcut so {@code ocean_monument} finds {@code monument}).
     */
    static String closest(Stream<Identifier> candidates, String input) {
        String want = pathOf(input);
        Identifier best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Identifier id : (Iterable<Identifier>) candidates::iterator) {
            String path = id.getPath();
            int dist;
            if (want.contains(path) || path.contains(want)) {
                // Containment: "ocean_monument" contains "monument"; "mansion"
                // is inside "woodland_mansion". Strongest signal.
                dist = 1;
            } else if (sharesMeaningfulToken(want, path)) {
                // Token overlap: "jungle_temple" and "jungle_pyramid" share
                // "jungle" — edit distance alone can't bridge temple↔pyramid.
                dist = 2;
            } else {
                dist = levenshtein(want, path);
            }
            if (dist < bestDist) {
                bestDist = dist;
                best = id;
            }
        }
        int threshold = Math.max(2, want.length() / 3);
        return (best != null && bestDist <= threshold) ? best.toString() : null;
    }

    /** Do the two paths share an underscore-token of 5+ chars ("jungle", "village")? */
    private static boolean sharesMeaningfulToken(String a, String b) {
        for (String ta : a.split("_")) {
            if (ta.length() < 5) continue;
            for (String tb : b.split("_")) {
                if (ta.equals(tb)) return true;
            }
        }
        return false;
    }

    private static String pathOf(String input) {
        int colon = input.indexOf(':');
        return (colon >= 0 ? input.substring(colon + 1) : input).toLowerCase();
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int sub = prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                cur[j] = Math.min(sub, Math.min(prev[j] + 1, cur[j - 1] + 1));
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
