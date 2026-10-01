package io.github.underconnor.passport.paper;
import io.github.underconnor.passport.core.*;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** Only a signed proxy request may cause a teleport; client channel messages are untrusted. */
final class TeleportReceiver implements PluginMessageListener,AutoCloseable {
    private final JavaPlugin plugin;
    private final String serverId,secret;
    private final Function<UUID,CompletableFuture<Policy>> fresh;
    private final Function<UUID,Optional<Policy>> current;
    private final TeleportReplayGuard replay=new TeleportReplayGuard(2048);
    private volatile boolean closed;
    TeleportReceiver(JavaPlugin plugin,String serverId,String secret,Function<UUID,CompletableFuture<Policy>> fresh,Function<UUID,Optional<Policy>> current) {
        this.plugin=plugin; this.serverId=serverId; this.secret=secret; this.fresh=fresh; this.current=current;
        var messenger=plugin.getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(plugin,TeleportMessage.CHANNEL);
        messenger.registerIncomingPluginChannel(plugin,TeleportMessage.CHANNEL,this);
    }
    @Override public void onPluginMessageReceived(String channel,Player carrier,byte[] bytes) {
        if(closed || !channel.equals(TeleportMessage.CHANNEL)) return;
        TeleportMessage request;
        try { request=TeleportMessage.decode(bytes,secret,Instant.now()); }
        catch(RuntimeException error) { return; }
        if(!request.kind().equals("REQUEST") || !request.serverId().equals(serverId) || !request.actor().equals(carrier.getUniqueId()) || !carrier.isOnline()) return;
        if(!replay.claim(request,Instant.now())) { reply(carrier,request,"denied"); return; }
        try {
            CompletableFuture<Policy> actor=fresh.apply(request.actor()),target=fresh.apply(request.target());
            CompletableFuture.allOf(actor,target).whenComplete((ignored,error) -> onMain(() -> {
                if(error!=null) { reply(carrier,request,"unavailable"); return; }
                teleport(carrier,request,actor.join(),target.join());
            }));
        } catch(RuntimeException error) { reply(carrier,request,"unavailable"); }
    }
    private boolean authorized(TeleportMessage request,Policy actor,Policy target,Instant now) {
        return actor!=null && target!=null && request.actor().equals(actor.minecraftUuid()) && request.target().equals(target.minecraftUuid())
            && actor.administrator() && actor.allows(serverId,now) && target.allows(serverId,now);
    }
    private void teleport(Player carrier,TeleportMessage request,Policy actorPolicy,Policy targetPolicy) {
        if(closed || !request.valid(Instant.now())) return;
        Player actor=plugin.getServer().getPlayer(request.actor()),target=plugin.getServer().getPlayer(request.target());
        if(actor!=carrier || target==null || !actor.isOnline() || !target.isOnline()) { reply(carrier,request,"unavailable"); return; }
        Instant now=Instant.now();
        if(!authorized(request,actorPolicy,targetPolicy,now) || !authorized(request,current.apply(request.actor()).orElse(null),current.apply(request.target()).orElse(null),now)) {
            reply(carrier,request,"denied"); return;
        }
        try {
            actor.teleportAsync(target.getLocation(),PlayerTeleportEvent.TeleportCause.PLUGIN)
                .whenComplete((success,error) -> onMain(() -> reply(carrier,request,error==null && Boolean.TRUE.equals(success) ? "ok" : "failed")));
        } catch(RuntimeException error) { reply(carrier,request,"failed"); }
    }
    private void reply(Player carrier,TeleportMessage request,String result) {
        if(closed || !plugin.isEnabled() || !request.valid(Instant.now()) || !carrier.isOnline() || plugin.getServer().getPlayer(request.actor())!=carrier) return;
        try { carrier.sendPluginMessage(plugin,TeleportMessage.CHANNEL,request.reply(result).encode(secret)); }
        catch(RuntimeException ignored) { /* Proxy timeout handles a disconnected carrier without exposing secrets. */ }
    }
    private void onMain(Runnable task) {
        if(closed || !plugin.isEnabled()) return;
        try { plugin.getServer().getScheduler().runTask(plugin,() -> { if(!closed && plugin.isEnabled()) task.run(); }); }
        catch(RuntimeException ignored) { /* Plugin shutdown may race a completed HTTP request. */ }
    }
    @Override public void close() {
        closed=true; replay.clear();
        var messenger=plugin.getServer().getMessenger();
        messenger.unregisterIncomingPluginChannel(plugin,TeleportMessage.CHANNEL,this);
        messenger.unregisterOutgoingPluginChannel(plugin,TeleportMessage.CHANNEL);
    }
}
