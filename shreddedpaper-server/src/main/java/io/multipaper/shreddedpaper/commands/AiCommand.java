package io.multipaper.shreddedpaper.commands;

import io.multipaper.shreddedpaper.ai.AiDiagnostics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import java.lang.reflect.ParameterizedType;
import java.util.HashMap;
import java.util.List;

/** Registry metadata only; this command never traverses live worlds or entities. */
public final class AiCommand extends Command {
    public AiCommand(String name) {
        super(name, "AI registry coverage and sampled timing", "/aiengine [namespace:type]", List.of());
        setPermission("shreddedpaper.command.aiengine");
    }
    @Override public boolean execute(CommandSender sender, String label, String[] args) {
        if (!testPermission(sender)) return true;
        if (args.length > 1) { sender.sendMessage(getUsage()); return true; }
        var classes = new HashMap<EntityType<?>, Class<?>>();
        for (var field : EntityType.class.getFields()) {
            if (field.getType() == EntityType.class && field.getGenericType() instanceof ParameterizedType generic
                && generic.getActualTypeArguments()[0] instanceof Class<?> type) {
                try { classes.put((EntityType<?>) field.get(null), type); }
                catch (IllegalAccessException ignored) { /* Unknown types remain explicitly unclassified. */ }
            }
        }
        sender.sendMessage("AI coverage: vanilla behavior retained; goal cleanup=" + AiDiagnostics.goalOptimizationEnabled()
            + "; timings are sampled wall time (including controls), not total CPU. Counters last until restart.");
        int listed = 0;
        for (var type : BuiltInRegistries.ENTITY_TYPE) {
            String key = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
            if (args.length == 1 && !key.equals(args[0])) continue;
            Class<?> nativeClass = classes.get(type);
            String coverage = nativeClass == null ? "unclassified; vanilla fallback"
                : Mob.class.isAssignableFrom(nativeClass) ? "vanilla AI; per-type validation pending" : "non-mob; outside AI scope";
            var sample = AiDiagnostics.sample(BuiltInRegistries.ENTITY_TYPE.getId(type));
            long count = sample == null ? 0 : sample.count();
            sender.sendMessage(key + ": " + coverage + "; samples=" + count
                + "; sampledNs=" + (sample == null ? 0 : sample.nanos()) + "; maxNs=" + (sample == null ? 0 : sample.maxNanos()));
            listed++;
        }
        if (listed == 0) sender.sendMessage("Unknown registry key; use a full namespace:type id.");
        return true;
    }
}
