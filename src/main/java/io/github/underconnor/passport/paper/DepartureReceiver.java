package io.github.underconnor.passport.paper;

import io.github.underconnor.passport.core.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

final class DepartureReceiver implements PluginMessageListener,AutoCloseable {
    private final JavaPlugin plugin;
    private final String serverId,secret;
    private final DepartureTracker tracker=new DepartureTracker();
    private boolean closed;
    DepartureReceiver(JavaPlugin plugin,String serverId,String secret) {
        this.plugin=plugin; this.serverId=serverId; this.secret=secret;
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin,DepartureMessage.CHANNEL,this);
        plugin.getServer().getScheduler().runTaskTimer(plugin,() -> tracker.tick(System.nanoTime()),1,1);
    }
    void joined(UUID actor) { tracker.joined(actor); }
    void quit(UUID actor,Consumer<Boolean> completed) { tracker.quit(actor,completed,System.nanoTime()); }
    @Override public void onPluginMessageReceived(String channel,Player carrier,byte[] bytes) {
        if(closed || !DepartureMessage.CHANNEL.equals(channel) || !carrier.isOnline()) return;
        try {
            var message=DepartureMessage.decode(bytes,secret,Instant.now());
            if(!serverId.equals(message.serverId())) return;
            if(message.kind().equals("BEGIN")) {
                if(message.actor().equals(carrier.getUniqueId())) tracker.begin(message);
            } else tracker.transferred(message);
        } catch(IllegalArgumentException ignored) { /* Client-origin and stale messages cannot change departure classification. */ }
    }
    @Override public void close() {
        closed=true; tracker.clear(); plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin,DepartureMessage.CHANNEL,this);
    }
}
