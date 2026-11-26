package io.evangelium.server.commands;

import net.minecraft.server.MinecraftServer;
import org.bukkit.command.Command;

import java.util.HashMap;
import java.util.Map;

public class EvangeliumServerCommands {

    private static final Map<String, Command> COMMANDS = new HashMap<>();
    static {
        for (Command command : new Command[] {
                new MPMapCommand("mpmap"),
                new AiCommand("aiengine"),
                new RegionsCommand("regions") // Evangelium - tick budget
        }) {
            COMMANDS.put(command.getName(), command);
        }
    }

    public static void registerCommands(final MinecraftServer server) {
        COMMANDS.forEach((s, command) -> {
            server.server.getCommandMap().register(s, "evangelium", command);   // shown as /evangelium:regions etc.
        });
    }

}
