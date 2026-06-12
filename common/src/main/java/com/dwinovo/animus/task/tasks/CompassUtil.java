package com.dwinovo.animus.task.tasks;

/**
 * 8-point compass label from a block delta (+X east, +Z south), shared by the
 * locate tools' result messages. A diagonal collapses to a cardinal when the
 * dominant axis is at least twice the other — "north-east" only when the
 * direction is meaningfully diagonal.
 */
final class CompassUtil {

    private CompassUtil() {}

    static String compass(int dx, int dz) {
        if (dx == 0 && dz == 0) return "here";
        String ns = dz < 0 ? "north" : (dz > 0 ? "south" : "");
        String ew = dx < 0 ? "west" : (dx > 0 ? "east" : "");
        if (ns.isEmpty()) return ew;
        if (ew.isEmpty()) return ns;
        if (Math.abs(dz) >= 2 * Math.abs(dx)) return ns;
        if (Math.abs(dx) >= 2 * Math.abs(dz)) return ew;
        return ns + "-" + ew;
    }
}
