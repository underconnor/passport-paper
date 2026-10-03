package io.github.underconnor.passport.paper;

import io.github.underconnor.passport.core.ProxyCommandMessage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.time.Clock;
import java.util.*;
import java.util.function.*;

/** Relays server-side player dispatch, such as a Citizens -p command, to the proxy. */
final class ProxyCommandRelay implements CommandExecutor,TabCompleter,Listener,AutoCloseable {
    private static final long COOLDOWN_NANOS=400_000_000L;
    private static final Component PLAYER_ONLY=Component.text("접속 중인 플레이어로 실행해 주세요. Citizens 명령에는 -p 옵션을 사용하세요.",NamedTextColor.RED);
    private static final Component UNAVAILABLE=Component.text("Passport 연결을 확인할 수 없습니다. 잠시 후 다시 시도하세요.",NamedTextColor.RED);
    private static final Component USAGE=Component.text("사용법: /passport help 또는 /서버 <서버명>",NamedTextColor.YELLOW);
    private final String serverId,secret;
    private final BooleanSupplier ready;
    private final Predicate<Player> allowed;
    private final Function<UUID,Player> onlinePlayer;
    private final BiConsumer<Player,byte[]> send;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final Runnable unregister;
    private final Map<UUID,Long> sentAt=new HashMap<>();
    private boolean closed;

    ProxyCommandRelay(JavaPlugin plugin,String serverId,String secret,BooleanSupplier ready,Predicate<Player> allowed) {
        this(serverId,secret,ready,allowed,plugin.getServer()::getPlayer,
            (player,bytes) -> player.sendPluginMessage(plugin,ProxyCommandMessage.CHANNEL,bytes),
            Clock.systemUTC(),System::nanoTime,
            () -> plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin,ProxyCommandMessage.CHANNEL));
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin,ProxyCommandMessage.CHANNEL);
    }

    ProxyCommandRelay(String serverId,String secret,BooleanSupplier ready,Predicate<Player> allowed,
        Function<UUID,Player> onlinePlayer,BiConsumer<Player,byte[]> send,Clock clock,LongSupplier nanoTime,Runnable unregister) {
        this.serverId=serverId; this.secret=secret; this.ready=ready; this.allowed=allowed;
        this.onlinePlayer=onlinePlayer; this.send=send; this.clock=clock; this.nanoTime=nanoTime; this.unregister=unregister;
    }

    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!(sender instanceof Player player) || player.hasMetadata("NPC") || !player.isOnline()
            || onlinePlayer.apply(player.getUniqueId())!=player) {
            sender.sendMessage(PLAYER_ONLY); return true;
        }
        if(closed || !ready.getAsBoolean() || !allowed.test(player)) { player.sendMessage(UNAVAILABLE); return true; }
        final String canonical;
        try { canonical=ProxyCommandMessage.normalize(label,args); }
        catch(IllegalArgumentException invalid) { player.sendMessage(USAGE); return true; }
        UUID actor=player.getUniqueId(); long now=nanoTime.getAsLong(); Long previous=sentAt.get(actor);
        if(previous!=null && now-previous<COOLDOWN_NANOS) return true;
        sentAt.put(actor,now);
        try { send.accept(player,ProxyCommandMessage.request(actor,serverId,canonical,clock.instant()).encode(secret)); }
        catch(RuntimeException unavailable) { player.sendMessage(UNAVAILABLE); }
        return true;
    }

    // Client-entered commands and their suggestions are owned by Velocity.
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) { return List.of(); }
    @EventHandler public void quit(PlayerQuitEvent event) { sentAt.remove(event.getPlayer().getUniqueId()); }
    @Override public void close() { if(closed) return; closed=true; sentAt.clear(); unregister.run(); }
}
