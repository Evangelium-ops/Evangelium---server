package io.evangelium.server.permissions;

import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.util.permissions.DefaultPermissions;

public class EvangeliumServerPermissions {
    private static final String ROOT = "evangeliumserver";

    public static void registerCorePermissions() {
        Permission parent = DefaultPermissions.registerPermission(ROOT, "Gives the user the ability to use all Evangelium server utilities and commands");

        DefaultPermissions.registerPermission(ROOT + ".maximumtrackerbypass", "Gives the user the ability to see all entities regardless of the maximum tracker config", PermissionDefault.OP, parent);

        EvangeliumServerCommandPermissions.registerPermissions(parent);

        parent.recalculatePermissibles();
        registerOldNames();
    }

    /**
     * The nodes were named evangeliumserver.* before the rename: each old node is registered with its new twin as a child, so a
     * LuckPerms setup that grants evangeliumserver.command.regions (or evangeliumserver itself) keeps working.
     */
    private static void registerOldNames() {
        org.bukkit.plugin.PluginManager plugins = org.bukkit.Bukkit.getPluginManager();
        for (Permission now : java.util.List.copyOf(plugins.getPermissions())) {
            String name = now.getName();
            if (!name.equals(ROOT) && !name.startsWith(ROOT + ".")) continue;
            String old = "shreddedpaper" + name.substring(ROOT.length());   // rename-keep
            if (plugins.getPermission(old) != null) continue;
            Permission alias = new Permission(old, "Old name of " + name, now.getDefault(), java.util.Map.of(name, true));
            plugins.addPermission(alias);
        }
    }
}
