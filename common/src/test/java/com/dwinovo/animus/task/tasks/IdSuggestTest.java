package com.dwinovo.animus.task.tasks;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The "did you mean" healer must catch the classic id traps and stay quiet otherwise. */
class IdSuggestTest {

    private static final List<Identifier> STRUCTURES = Stream.of(
                    "mansion", "monument", "jungle_pyramid", "desert_pyramid",
                    "stronghold", "fortress", "ancient_city", "village_plains")
            .map(p -> Identifier.fromNamespaceAndPath("minecraft", p))
            .toList();

    private static String closest(String input) {
        return IdSuggest.closest(STRUCTURES.stream(), input);
    }

    @Test
    void healsTheClassicTraps() {
        assertEquals("minecraft:mansion", closest("minecraft:woodland_mansion"));
        assertEquals("minecraft:monument", closest("ocean_monument"));
        assertEquals("minecraft:jungle_pyramid", closest("jungle_temple"));
    }

    @Test
    void healsTypos() {
        assertEquals("minecraft:stronghold", closest("stronghol"));
        assertEquals("minecraft:fortress", closest("fortres"));
    }

    @Test
    void staysQuietOnNonsense() {
        assertNull(closest("xyzzy_qwerty"));
    }

    @Test
    void levenshteinBasics() {
        assertEquals(0, IdSuggest.levenshtein("abc", "abc"));
        assertEquals(1, IdSuggest.levenshtein("abc", "abd"));
        assertEquals(3, IdSuggest.levenshtein("abc", ""));
    }
}
