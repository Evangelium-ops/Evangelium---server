package io.multipaper.shreddedpaper.util;

import java.util.Set;

/**
 * Developer benchmarks only (before/after runs and verify modes); not a server setting. Every optimisation is on unless the
 * hidden property -Devangelium.dev.off=<comma separated names, or all> names it. Read once at class load; callers keep the answer
 * in a static final.
 */
public final class DevSwitch {
    private static final Set<String> OFF = Set.copyOf(java.util.Arrays.asList(System.getProperty("evangelium.dev.off", "").replace(" ", "").split(","))); // copyOf: duplicates allowed
    private static final boolean ALL = OFF.contains("all");

    private DevSwitch() {
    }

    public static boolean on(String name) {
        return !ALL && !OFF.contains(name);
    }
}
