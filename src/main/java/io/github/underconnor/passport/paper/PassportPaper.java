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
    private final Set<UUID> refreshing = ConcurrentHashMap.newKeySet();
    private ApiClient api;
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
            ready=true;
            Bukkit.getScheduler().runTaskTimer(this,() -> Bukkit.getOnlinePlayers().forEach(player -> { if(!allowed(player)) player.kick(DENIED); }),1,20);
            Bukkit.getScheduler().runTaskTimer(this,() -> Bukkit.getOnlinePlayers().forEach(this::refresh),20,400);
            getLogger().info("Passport enabled; fail-closed server ID: "+serverId);
        } catch(RuntimeException error) { getLogger().severe("Passport configuration invalid; admission closed: "+error.getMessage()); }
        // Hot loading/reloading must not retain players whose leases have not been checked.
        Bukkit.getOnlinePlayers().forEach(player -> { if(!allowed(player)) player.kick(DENIED); });
    }
    @Override public void onDisable() { ready=false; if(api!=null) api.close(); }
    @EventHandler(priority=EventPriority.HIGHEST) public void preLogin(AsyncPlayerPreLoginEvent event) {
        if(!ready) { event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,DENIED); return; }
        try {
            Policy policy=api.policy(event.getUniqueId()).get(2100,TimeUnit.MILLISECONDS);
            policies.accept(policy);
            if(!policies.allows(event.getUniqueId(),serverId,Instant.now())) event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST,DENIED);
        } catch(Exception error) { event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,DENIED); }
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void login(PlayerLoginEvent event) {
        if(!allowed(event.getPlayer())) event.disallow(PlayerLoginEvent.Result.KICK_WHITELIST,DENIED);
    }
    @EventHandler public void join(PlayerJoinEvent event) { if(!allowed(event.getPlayer())) event.getPlayer().kick(DENIED); else display(event.getPlayer()); }
    private boolean allowed(Player player) { return ready && policies.allows(player.getUniqueId(),serverId,Instant.now()); }
    private void refresh(Player player) {
        UUID uuid=player.getUniqueId();
        if(!refreshing.add(uuid)) return;
        api.policy(uuid).whenComplete((policy,error) -> {
            refreshing.remove(uuid);
            if(!ready) return;
            if(error==null) policies.accept(policy);
            Bukkit.getScheduler().runTask(this,() -> {
                if(!player.isOnline()) return;
                if(!allowed(player)) player.kick(DENIED); else display(player);
            });
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
