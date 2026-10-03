package io.github.underconnor.passport.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import java.util.*;
import java.util.function.*;

/** Keeps Paper's existing Brigadier permission filtering; only removes known denied commands. */
final class CommandVisibility implements Listener,CommandExecutor,TabCompleter {
    static final String INSPECT="passport.commands.inspect";
    private static final Set<String> INFORMATION=Set.of("plugins","pl","version","ver","about","icanhasbukkit","help","?");
    private static final Set<String> HELP=Set.of("help","?","도움말");
    record Entry(String command,String description,String permission,boolean proxy) {}
    private final Function<String,Command> commands;
    private final Predicate<Player> allowed;
    private final List<Entry> entries;
    CommandVisibility(Function<String,Command> commands,Predicate<Player> allowed,List<Entry> entries) {
        this.commands=commands; this.allowed=allowed; this.entries=List.copyOf(entries);
    }
    static List<Entry> read(ConfigurationSection config) {
        List<Entry> entries=new ArrayList<>();
        for(Map<?,?> row:config.getMapList("help.entries")) {
            String command=string(row,"command"),description=string(row,"description"),permission=string(row,"permission");
            if(!command.startsWith("/") || command.length()>160 || description.length()>120 || permission.length()>120
                || command.codePoints().anyMatch(Character::isISOControl) || description.codePoints().anyMatch(Character::isISOControl)
                || !command.substring(1).matches("[^\\s]+(?: [^\\r\\n]*)?")) throw new IllegalArgumentException("Invalid help entry");
            Object rawProxy=row.containsKey("proxy") ? row.get("proxy") : Boolean.FALSE;
            if(!(rawProxy instanceof Boolean proxy)) throw new IllegalArgumentException("help.entries.proxy must be boolean");
            // These fixed public proxy routes have their own current-policy checks in Velocity.
            if(proxy && !Set.of("/passport help","/서버").contains(command)) throw new IllegalArgumentException("Unknown proxy help entry");
            entries.add(new Entry(command,description,permission,proxy));
            if(entries.size()>24) throw new IllegalArgumentException("At most 24 help entries are supported");
        }
        return List.copyOf(entries);
    }
    private static String string(Map<?,?> row,String key) { Object value=row.get(key); return value==null ? "" : value.toString().strip(); }
    static String root(String input) {
        String command=input.startsWith("/") ? input.substring(1) : input;
        return command.strip().split("\\s+",2)[0].toLowerCase(Locale.ROOT);
    }
    private static String bare(String root) { return root.substring(root.lastIndexOf(':')+1); }
    private boolean visible(Player player,String root) {
        if(!player.hasPermission(INSPECT) && INFORMATION.contains(bare(root)) && !HELP.contains(root)) return false;
        Command command=commands.apply(root);
        // Unknown native Brigadier commands have already been filtered by Paper. Do not guess their permissions.
        return command==null || command.testPermissionSilent(player);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void send(PlayerCommandSendEvent event) {
        event.getCommands().removeIf(label -> !visible(event.getPlayer(),root(label)));
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void execute(PlayerCommandPreprocessEvent event) {
        String command=root(event.getMessage());
        if(HELP.contains(command)) { event.setCancelled(true); show(event.getPlayer()); }
        else if(!event.getPlayer().hasPermission(INSPECT) && INFORMATION.contains(bare(command))) {
            event.setCancelled(true);
            if(HELP.contains(bare(command))) show(event.getPlayer());
            else event.getPlayer().sendMessage(Component.text("이 명령은 사용할 수 없습니다. /help에서 사용 가능한 명령을 확인하세요.",NamedTextColor.GRAY));
        }
    }
    List<Entry> available(Player player) {
        if(!allowed.test(player)) return List.of();
        return entries.stream().filter(entry -> {
            if(!entry.permission().isEmpty() && !player.hasPermission(entry.permission())) return false;
            if(entry.proxy()) return true;
            String root=root(entry.command()); Command command=commands.apply(root);
            return command!=null && visible(player,root);
        }).toList();
    }
    private void show(Player player) {
        if(!allowed.test(player)) return;
        player.sendMessage(Component.text("사용 가능한 명령어 · 눌러서 입력",NamedTextColor.GREEN));
        for(Entry entry:available(player)) player.sendMessage(Component.text(entry.command(),NamedTextColor.WHITE)
            .append(Component.text(" — "+entry.description(),NamedTextColor.GRAY))
            .clickEvent(ClickEvent.suggestCommand(entry.command()+" ")));
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(sender instanceof Player player) show(player);
        else sender.sendMessage("게임 안에서 /help를 사용하세요. 서버 명령 목록은 /bukkit:help로 확인할 수 있습니다.");
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) { return List.of(); }
}
