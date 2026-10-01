package io.github.underconnor.passport.paper;

import io.github.underconnor.passport.core.*;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

public final class PassportPaper extends JavaPlugin implements Listener {
    private final PolicyCache policies = new PolicyCache();
    private final Map<UUID, Player> onlinePlayers = new ConcurrentHashMap<>();
    private ApiClient api;
    private PolicyRefreshes refreshes;
    private PolicyEventPoller eventPoller;
    private ServerHeartbeat heartbeat;
    private String serverId;
    private volatile boolean ready;
    private boolean chatPrefix, tabPrefix;
    private static final Component DENIED=Component.text("Passport 서버 접근 권한을 확인할 수 없습니다. 잠시 후 다시 접속하세요.",NamedTextColor.RED);
    @Override public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this,this);
        try {
            serverId=ApiClient.env("PASSPORT_SERVER_ID","");
            if(!serverId.matches("[a-z][a-z0-9_-]{0,63}")) throw new IllegalArgumentException("PASSPORT_SERVER_ID is required");
            api=new ApiClient(ApiClient.env("PASSPORT_API_BASE_URL","https://api.passport.example/"),System.getenv("API_SERVICE_TOKEN"),
                Boolean.parseBoolean(ApiClient.env("PASSPORT_ALLOW_INSECURE_HTTP","false")));
            chatPrefix=Boolean.parseBoolean(ApiClient.env("PASSPORT_CHAT_PREFIX","true"));
            tabPrefix=Boolean.parseBoolean(ApiClient.env("PASSPORT_TAB_PREFIX","true"));
            refreshes=new PolicyRefreshes(uuid -> api.policy(uuid).thenApply(policy -> {
                if(!policies.acceptOrCurrent(policy)) throw new CompletionException(new IllegalStateException("Stale policy response"));
                return policy;
            }));
            eventPoller=new PolicyEventPoller(api::events,() -> Set.copyOf(onlinePlayers.keySet()),this::refreshFromEvent);
            heartbeat=new ServerHeartbeat(() -> api.heartbeat("paper",List.of(new ServerRegistration(serverId,
                ApiClient.env("PASSPORT_SERVER_LABEL",serverId)))),available -> {
                if(ready) { if(available) getLogger().info("Passport server registration recovered");
                    else getLogger().warning("Passport server registration unavailable; existing access checks remain active"); }
            });
            ready=true;
            heartbeat.poll().exceptionally(error -> null);
            Bukkit.getScheduler().runTaskTimer(this,() -> {
                if(ready) heartbeat.poll().exceptionally(error -> null);
            },600,600);
            Bukkit.getScheduler().runTaskTimer(this,() -> Bukkit.getOnlinePlayers().forEach(player -> { if(!allowed(player)) player.kick(DENIED); }),1,20);
            Bukkit.getScheduler().runTaskTimer(this,() -> Bukkit.getOnlinePlayers().forEach(this::refresh),20,400);
            Bukkit.getScheduler().runTaskTimer(this,() -> {
                if(ready) eventPoller.poll().exceptionally(error -> null);
            },1,40);
            getLogger().info("Passport enabled; fail-closed server ID: "+serverId);
        } catch(RuntimeException error) { getLogger().severe("Passport configuration invalid; admission closed: "+error.getMessage()); }
        // Hot loading/reloading must not retain players whose leases have not been checked.
        Bukkit.getOnlinePlayers().forEach(player -> { if(!allowed(player)) player.kick(DENIED); });
    }
    @Override public void onDisable() { ready=false; onlinePlayers.clear(); if(api!=null) api.close(); }
    @EventHandler(priority=EventPriority.HIGHEST) public void preLogin(AsyncPlayerPreLoginEvent event) {
        if(!ready) { event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,DENIED); return; }
        try {
            refreshes.fetch(event.getUniqueId()).get(2100,TimeUnit.MILLISECONDS);
            if(!policies.allows(event.getUniqueId(),serverId,Instant.now())) event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST,DENIED);
        } catch(Exception error) { event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,DENIED); }
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void login(PlayerLoginEvent event) {
        if(!allowed(event.getPlayer())) event.disallow(PlayerLoginEvent.Result.KICK_WHITELIST,DENIED);
    }
    @EventHandler public void join(PlayerJoinEvent event) {
        if(!allowed(event.getPlayer())) event.getPlayer().kick(DENIED);
        else { onlinePlayers.put(event.getPlayer().getUniqueId(),event.getPlayer()); display(event.getPlayer()); }
    }
    @EventHandler public void quit(PlayerQuitEvent event) { onlinePlayers.remove(event.getPlayer().getUniqueId(),event.getPlayer()); }
    private boolean allowed(Player player) { return ready && policies.allows(player.getUniqueId(),serverId,Instant.now()); }
    private void refresh(Player player) { refreshFromEvent(player.getUniqueId(),false).exceptionally(error -> null); }
    private CompletableFuture<Policy> refreshFromEvent(UUID uuid,boolean reset) {
        if(!ready || !onlinePlayers.containsKey(uuid)) return CompletableFuture.completedFuture(null);
        return (reset ? refreshes.fresh(uuid) : refreshes.fetch(uuid)).thenCompose(policy -> {
            CompletableFuture<Policy> applied=new CompletableFuture<>();
            if(!ready) return CompletableFuture.failedFuture(new IllegalStateException("Plugin stopping"));
            // All Bukkit player actions run on the main thread; HTTP and policy parsing never block it.
            try { Bukkit.getScheduler().runTask(this,() -> {
                Player player=onlinePlayers.get(uuid);
                if(player!=null && player.isOnline()) {
                    if(!allowed(player)) player.kick(DENIED); else display(player);
                }
                applied.complete(policy);
            }); } catch(RuntimeException error) { applied.completeExceptionally(error); }
            return applied;
        });
    }
    private Component prefix(Player player) {
        return policies.get(player.getUniqueId()).map(policy -> policy.roleLabel().isBlank() ? Component.empty()
            : Component.text("["+policy.roleLabel()+"] ",NamedTextColor.AQUA)).orElse(Component.empty());
    }
    private void display(Player player) { if(tabPrefix) player.playerListName(prefix(player).append(Component.text(player.getName(),NamedTextColor.WHITE))); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void chat(AsyncChatEvent event) {
        if(!allowed(event.getPlayer())) { event.setCancelled(true); return; }
        if(chatPrefix) event.renderer((source,sourceDisplayName,message,viewer) -> prefix(source).append(Component.text(source.getName()))
            .append(Component.text(": ")).append(message));
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void move(PlayerMoveEvent event) { if(!allowed(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void interact(PlayerInteractEvent event) { if(!allowed(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void breakBlock(BlockBreakEvent event) { if(!allowed(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void placeBlock(BlockPlaceEvent event) { if(!allowed(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void command(PlayerCommandPreprocessEvent event) { if(!allowed(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void inventory(InventoryClickEvent event) { if(event.getWhoClicked() instanceof Player p && !allowed(p)) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void damage(EntityDamageByEntityEvent event) { if(event.getDamager() instanceof Player p && !allowed(p)) event.setCancelled(true); }
}
