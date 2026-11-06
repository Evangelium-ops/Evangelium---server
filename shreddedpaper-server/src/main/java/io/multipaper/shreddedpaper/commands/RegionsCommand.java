package io.multipaper.shreddedpaper.commands;

import io.multipaper.shreddedpaper.region.LevelChunkRegion;
import io.multipaper.shreddedpaper.threading.RegionBudget;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** /regions lists current lag; /regions logs also includes the recent event history. */
public class RegionsCommand extends Command {

    public RegionsCommand(String name) {
        super(name, "Shows the regions slowed down by the tick budget", "/regions [logs]", List.of());
        setPermission("shreddedpaper.command.regions");
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
        if (!testPermission(sender)) {
            return true;
        }
        if (args.length > 1 || (args.length == 1 && !args[0].equalsIgnoreCase("logs"))) {
            sender.sendMessage(Component.text("Usage: /regions [logs]", NamedTextColor.RED));
            return true;
        }
        if (!RegionBudget.enabled()) {
            sender.sendMessage(Component.text("The region tick budget is off (region-budget-ms in launcher.properties).", NamedTextColor.GRAY));
        }
        Collection<Map.Entry<LevelChunkRegion, RegionBudget.State>> slow = RegionBudget.slowRegions();
        if (slow.isEmpty()) {
            sender.sendMessage(Component.text("No regions detected.", NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text(slow.size() == 1 ? "Region detected at:" : "Regions detected at:", NamedTextColor.RED));
            for (Map.Entry<LevelChunkRegion, RegionBudget.State> e : slow.stream()
                    .sorted(java.util.Comparator.comparing(entry -> RegionBudget.where(entry.getKey()))).toList()) {
                RegionBudget.State s = e.getValue();
                sender.sendMessage(Component.text(RegionBudget.where(e.getKey()) + " - " + RegionBudget.summary(s), NamedTextColor.RED)
                        .hoverEvent(HoverEvent.showText(Component.text("Click to teleport there")))
                        .clickEvent(ClickEvent.runCommand(RegionBudget.teleport(e.getKey()))));
                if (args.length == 1) {
                    sender.sendMessage(Component.text("   " + RegionBudget.costs(s), NamedTextColor.GRAY));
                }
            }
            if (slow.size() == 1) sender.sendMessage(Component.text("No other regions detected.", NamedTextColor.GRAY));
        }
        if (args.length == 0) return true;
        List<String> log = RegionBudget.log();
        if (!log.isEmpty()) {
            sender.sendMessage(Component.text("Last " + Math.min(15, log.size()) + " events:", NamedTextColor.GRAY));
            for (String line : log.subList(Math.max(0, log.size() - 15), log.size())) {
                sender.sendMessage(Component.text(" " + line, line.contains("is lagging") ? NamedTextColor.RED : NamedTextColor.GRAY));
            }
        }
        return true;
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args) {
        return args.length == 1 && "logs".startsWith(args[0].toLowerCase()) ? List.of("logs") : List.of();
    }
}
