package io.multipaper.shreddedpaper.permissions;

import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.util.permissions.DefaultPermissions;
import org.jetbrains.annotations.NotNull;

public class ShreddedPaperCommandPermissions {
    private static final String ROOT = "shreddedpaper.command";
    private static final String PREFIX = ROOT + ".";

    public static void registerPermissions(@NotNull Permission parent) {
        Permission commands = DefaultPermissions.registerPermission(ROOT, "Gives the user the ability to use all Evangelium server commands", parent);

        DefaultPermissions.registerPermission(PREFIX + "mpmap", "MPMap command", PermissionDefault.TRUE, commands);
        DefaultPermissions.registerPermission(PREFIX + "regions", "Regions command (laggy regions)", PermissionDefault.OP, commands); // Knowledge
        DefaultPermissions.registerPermission("shreddedpaper.regions.alerts", "Told when a region lags", PermissionDefault.OP, parent); // Knowledge

        DefaultPermissions.registerPermission(PREFIX + "aiengine", "AI diagnostics and coverage", PermissionDefault.OP, commands);

        commands.recalculatePermissibles();
    }
}
