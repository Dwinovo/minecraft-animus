package com.dwinovo.animus.task.tasks;

/**
 * Expanding square-ring spiral enumeration, shared by the structure and biome
 * locators (one walks placement regions, the other walks sample-grid cells).
 *
 * <p>Ring 0 is the single center cell; ring {@code r > 0} is the 8r-cell
 * perimeter of the (2r+1)² square, enumerated side by side: top edge west→east
 * (sans the NE corner), east edge north→south (sans SE), bottom edge east→west
 * (sans SW), west edge south→north (sans NW). Every cell of every ring is
 * visited exactly once; the union over rings 0..N is the full (2N+1)² grid.
 */
final class RingSpiral {

    private RingSpiral() {}

    /** Number of cells on {@code ring}'s perimeter (1 for the center ring). */
    static int perimeter(int ring) {
        return ring == 0 ? 1 : 8 * ring;
    }

    /**
     * The {@code idx}-th cell of {@code ring}'s perimeter as a (dx, dz) offset
     * from the spiral center. {@code idx} must be in {@code [0, perimeter(ring))}.
     */
    static int[] offset(int ring, int idx) {
        if (ring == 0) return new int[]{0, 0};
        int side = idx / (2 * ring);
        int t = idx % (2 * ring);
        return switch (side) {
            case 0 -> new int[]{-ring + t, -ring};
            case 1 -> new int[]{ring, -ring + t};
            case 2 -> new int[]{ring - t, ring};
            default -> new int[]{-ring, ring - t};
        };
    }
}
