package io.github.underconnor.passport.paper;

import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import java.util.*;

/** Additional visibility checks for plugins whose public command root has no permission. */
final class ManagedCommandAccess {
    enum Family { NONE, LUCKPERMS, WORLDEDIT, MULTIVERSE, PLACEHOLDER, LOBBY_ADMIN }
    private static final Set<String> LUCKPERMS=Set.of("lp","luckperms","perm","perms","permission","permissions");
    private static final Set<String> WORLDEDIT=Set.of("fawe","fastasyncworldedit","worldedit","we");
    private static final Set<String> PLACEHOLDER_PERMISSIONS=Set.of("placeholderapi.*","placeholderapi.admin","placeholderapi.help",
        "placeholderapi.info","placeholderapi.list","placeholderapi.parse","placeholderapi.reload","placeholderapi.version",
        "placeholderapi.register","placeholderapi.unregister","placeholderapi.ecloud","placeholderapi.ecloud.*");
    record Rule(Command command,Family family) {}

    static Rule rule(String label,Command command) {
        String bare=label.substring(label.lastIndexOf(':')+1);
        String namespace=label.contains(":") ? label.substring(0,label.indexOf(':')) : "";
        String owner=command instanceof PluginIdentifiableCommand owned ? owned.getPlugin().getName().toLowerCase(Locale.ROOT) : "";
        // Keep public /lobby and /spawn, but do not advertise the management alias/namespace.
        if(bare.equals("overworldlobby") || namespace.equals("overworldlobby") || (bare.equals("setspawn") && owner.equals("overworldlobby")))
            return new Rule(command,Family.LOBBY_ADMIN);
        if(owner.equals("luckperms") || namespace.equals("luckperms") || LUCKPERMS.contains(bare)) return new Rule(command,Family.LUCKPERMS);
        if(owner.equals("fastasyncworldedit") || owner.equals("worldedit") || WORLDEDIT.contains(namespace) || WORLDEDIT.contains(bare))
            return new Rule(command,Family.WORLDEDIT);
        if(owner.equals("multiverse-core") || namespace.equals("multiverse-core") || namespace.equals("multiverse") || Set.of("mv","multiverse","multiverse-core").contains(bare))
            return new Rule(command,Family.MULTIVERSE);
        if(owner.equals("placeholderapi") || namespace.equals("placeholderapi") || Set.of("placeholderapi","papi").contains(bare))
            return new Rule(command,Family.PLACEHOLDER);
        return new Rule(command,Family.NONE);
    }

    static boolean visible(Player player,String label,Rule rule,Set<String> registeredPermissions) {
        Command command=rule.command();
        if(command!=null && !command.testPermissionSilent(player)) return false;
        if(rule.family()==Family.LOBBY_ADMIN) return player.hasPermission("overworld.lobby.admin");
        if(rule.family()==Family.NONE) return true;
        // FAWE and Multiverse wrappers can provide a real per-command predicate; retain it.
        if(command!=null && command.getPermission()!=null && !command.getPermission().isBlank()) return true;
        if(rule.family()==Family.WORLDEDIT && command!=null && !worldEditCondition(player,command)) return false;
        return switch(rule.family()) {
            case LUCKPERMS -> luckPermsHasCommands(player,label,command);
            case PLACEHOLDER -> PLACEHOLDER_PERMISSIONS.stream().anyMatch(player::hasPermission);
            case WORLDEDIT -> hasAny(player,registeredPermissions,ManagedCommandAccess::worldEditPermission,
                "worldedit.*","worldedit.help","worldedit.wand","fawe.admin","fawe.reload","fawe.permpack.basic");
            case MULTIVERSE -> hasAny(player,registeredPermissions,ManagedCommandAccess::multiversePermission,
                "multiverse.*","multiverse.core.*","multiverse.core.list","multiverse.core.info","multiverse.core.create");
            default -> false;
        };
    }

    private static boolean worldEditCondition(Player player,Command command) {
        // FAWE's wrapper skips its inspector when its permission array is empty. Consult the
        // public inspector API as well, so a single WorldEdit grant does not reveal other tools.
        if(!command.getClass().getName().equals("com.sk89q.bukkit.util.DynamicPluginCommand")) return true;
        try {
            Object inspector=command.getClass().getMethod("getRegisteredWith").invoke(command);
            Class<?> type=Class.forName("com.sk89q.bukkit.util.CommandInspector",false,command.getClass().getClassLoader());
            if(!type.isInstance(inspector)) return false;
            return Boolean.TRUE.equals(type.getMethod("testPermission",CommandSender.class,Command.class).invoke(inspector,player,command));
        } catch(ReflectiveOperationException | RuntimeException unavailable) { return false; }
    }

    private static boolean luckPermsHasCommands(Player player,String label,Command command) {
        // Calling the installed LP completer directly avoids Bukkit's player-name fallback.
        if(!(command instanceof PluginCommand pluginCommand) || pluginCommand.getTabCompleter()==null) return false;
        try {
            List<String> suggestions=pluginCommand.getTabCompleter().onTabComplete(player,command,label,new String[]{""});
            return suggestions!=null && !suggestions.isEmpty();
        } catch(RuntimeException unavailable) { return false; }
    }
    private static boolean hasAny(Player player,Set<String> registered,java.util.function.Predicate<String> relevant,String... anchors) {
        for(String permission:anchors) if(player.hasPermission(permission)) return true;
        for(String permission:registered) if(relevant.test(permission) && player.hasPermission(permission)) return true;
        for(PermissionAttachmentInfo permission:player.getEffectivePermissions())
            if(permission.getValue() && relevant.test(permission.getPermission()) && player.hasPermission(permission.getPermission())) return true;
        return false;
    }
    private static boolean worldEditPermission(String permission) {
        return permission.startsWith("worldedit.") || Set.of("fawe.admin","fawe.reload","fawe.cancel","fawe.worldeditregion","fawe.permpack.basic","fawe.*").contains(permission);
    }
    private static boolean multiversePermission(String permission) {
        // World access/exempt permissions alone do not grant a Multiverse command.
        return permission.startsWith("multiverse.core.") || permission.startsWith("multiverse.teleport.") || permission.startsWith("multiverse.spawn.") || permission.equals("multiverse.*");
    }
}
