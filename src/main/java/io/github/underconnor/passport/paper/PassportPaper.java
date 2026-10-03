package io.github.underconnor.passport.paper;

import io.github.underconnor.passport.core.*;
import io.github.underconnor.passport.api.*;
import org.bukkit.plugin.ServicePriority;
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
    private final java.util.concurrent.atomic.AtomicBoolean presenceRunning=new java.util.concurrent.atomic.AtomicBoolean();
    private String serverId;
    private volatile boolean ready;
    private DisplaySettings displaySettings;
    private IdentityDisplay identityDisplay;
    private DisplayEvents displayEvents;
    private final NameTags nameTags=new NameTags();
    private PassportPlaceholders placeholders;
    private StatisticsCollector statistics;
    private TeleportReceiver teleports;
    private DepartureReceiver departures;
    private ProxyCommandRelay proxyCommands;
    private final Map<UUID,String> onlineNames=new ConcurrentHashMap<>();
    private final PassportIdentityService identities=new PassportIdentityService() {
        @Override public Optional<PassportIdentity> identity(UUID uuid) {
            return !ready || !onlineNames.containsKey(uuid) ? Optional.empty() : policies.get(uuid)
                .filter(policy -> policy.allows(serverId,Instant.now()) && !policy.displayName().isBlank())
                .map(policy -> new PassportIdentity(uuid,policy.displayName(),policy.member(),policy.admissionYear(),policy.expiresAt()));
        }
        @Override public List<UUID> resolveOnline(String query) {
            if(query==null || query.isBlank()) return List.of();
            return onlineNames.entrySet().stream().filter(entry -> identity(entry.getKey()).map(identity ->
                entry.getValue().equalsIgnoreCase(query) || identity.realName().equals(query)).orElse(false))
                .map(Map.Entry::getKey).sorted().toList();
        }
    };
    private static final Component DENIED=Component.text("Passport 서버 접근 권한을 확인할 수 없습니다. 잠시 후 다시 접속하세요.",NamedTextColor.RED);
    @Override public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this,this);
        try {
            serverId=ApiClient.env("PASSPORT_SERVER_ID","");
            if(!serverId.matches("[a-z][a-z0-9_-]{0,63}")) throw new IllegalArgumentException("PASSPORT_SERVER_ID is required");
            Bukkit.getPluginManager().registerEvents(new LoginAdmission(() -> ready,policies::get,serverId,DENIED,java.time.Clock.systemUTC()),this);
            api=new ApiClient(ApiClient.env("PASSPORT_API_BASE_URL","https://api.passport.example/"),System.getenv("API_SERVICE_TOKEN"),
                Boolean.parseBoolean(ApiClient.env("PASSPORT_ALLOW_INSECURE_HTTP","false")));
            saveDefaultConfig();
            displaySettings=DisplaySettings.read(getConfig(),System::getenv);
            identityDisplay=new IdentityDisplay(displaySettings);
            String proxySecret=TeleportSecrets.resolve(System.getenv("PASSPORT_TELEPORT_SECRET"),System.getenv("API_SERVICE_TOKEN"));
            departures=new DepartureReceiver(this,serverId,proxySecret);
            displayEvents=new DisplayEvents(displaySettings,() -> ready,policies::get,serverId,java.time.Clock.systemUTC(),departures::joined,departures::quit,
                leaving -> {
                    var audience=Bukkit.getOnlinePlayers().stream().filter(viewer -> viewer!=leaving && viewer.canSee(leaving)).toList();
                    return message -> { audience.stream().filter(Player::isOnline).forEach(viewer -> viewer.sendMessage(message)); getServer().getConsoleSender().sendMessage(message); };
                });
            Bukkit.getPluginManager().registerEvents(displayEvents,this);
            CommandVisibility commandVisibility=new CommandVisibility(Bukkit.getCommandMap()::getCommand,this::allowed,CommandVisibility.read(getConfig()),
                () -> Bukkit.getPluginManager().getPermissions().stream().map(org.bukkit.permissions.Permission::getName).collect(java.util.stream.Collectors.toSet()),this::completeOnServerThread);
            Bukkit.getPluginManager().registerEvents(commandVisibility,this);
            Objects.requireNonNull(getCommand("help")).setExecutor(commandVisibility);
            Objects.requireNonNull(getCommand("help")).setTabCompleter(commandVisibility);
            proxyCommands=new ProxyCommandRelay(this,serverId,proxySecret,() -> ready,this::allowed);
            Bukkit.getPluginManager().registerEvents(proxyCommands,this);
            Objects.requireNonNull(getCommand("passport")).setExecutor(proxyCommands);
            Objects.requireNonNull(getCommand("passport")).setTabCompleter(proxyCommands);
            Bukkit.getServicesManager().register(PassportIdentityService.class,identities,this,ServicePriority.Normal);
            if(Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) { placeholders=new PassportPlaceholders(identities); placeholders.register(); }
            refreshes=new PolicyRefreshes(uuid -> api.policy(uuid).thenApply(policy -> {
                if(!policies.acceptOrCurrent(policy)) throw new CompletionException(new IllegalStateException("Stale policy response"));
                return policy;
            }));
            teleports=new TeleportReceiver(this,serverId,proxySecret,refreshes::fresh,policies::get);
            eventPoller=new PolicyEventPoller(api::events,() -> Set.copyOf(onlinePlayers.keySet()),this::refreshFromEvent);
            heartbeat=new ServerHeartbeat(() -> api.heartbeat("paper",List.of(new ServerRegistration(serverId,
                ApiClient.env("PASSPORT_SERVER_LABEL",serverId)))),available -> {
                if(ready) { if(available) getLogger().info("Passport server registration recovered");
                    else getLogger().warning("Passport server registration unavailable; existing access checks remain active"); }
            });
            try {
                statistics=new StatisticsCollector(this,api,serverId,player -> policies.get(player.getUniqueId())
                    .filter(policy -> ready && policy.collectsStatistics(serverId,Instant.now())).map(Policy::telemetryEpoch).orElse(null));
                Bukkit.getPluginManager().registerEvents(statistics,this);
                Bukkit.getScheduler().runTaskTimer(this,() -> { if(ready) statistics.second(Bukkit.getOnlinePlayers()); },20,20);
            } catch(java.io.IOException error) { getLogger().severe("Passport statistics unavailable; preserve statistics.json for recovery. Admission checks remain active."); }
            ready=true;
            heartbeat.poll().exceptionally(error -> null);
            Bukkit.getScheduler().runTaskTimer(this,() -> {
                if(ready) { heartbeat.poll().exceptionally(error -> null); presence(); }
            },600,600);
            Bukkit.getScheduler().runTaskTimer(this,() -> Bukkit.getOnlinePlayers().forEach(player -> { if(!allowed(player)) player.kick(DENIED); }),1,20);
            Bukkit.getScheduler().runTaskTimer(this,() -> Bukkit.getOnlinePlayers().forEach(this::refresh),20,400);
            Bukkit.getScheduler().runTaskTimer(this,() -> {
                if(ready) eventPoller.poll().exceptionally(error -> null);
            },1,40);
            getLogger().info("Passport enabled; fail-closed server ID: "+serverId);
        } catch(Exception error) { getLogger().severe("Passport configuration invalid; admission closed: "+error.getMessage()); }
        // Hot loading/reloading must not retain players whose leases have not been checked.
        Bukkit.getOnlinePlayers().forEach(player -> { if(!allowed(player)) player.kick(DENIED); });
    }
    private void presence() {
        if(!ready || !presenceRunning.compareAndSet(false,true)) return;
        api.presence(serverId,onlinePlayers.keySet().stream().filter(uuid -> policies.get(uuid).map(policy -> policy.presenceEnabled() && policy.allows(serverId,Instant.now())).orElse(false)).toList()).whenComplete((ignored,error) -> presenceRunning.set(false));
    }
    private Optional<CommandVisibility.CompletionDecision> completeOnServerThread(java.util.function.Supplier<CommandVisibility.CompletionDecision> work) {
        Future<CommandVisibility.CompletionDecision> future;
        try {
            if(Bukkit.isPrimaryThread()) return Optional.of(work.get());
            future=Bukkit.getScheduler().callSyncMethod(this,work::get);
        } catch(RuntimeException unavailable) { return Optional.empty(); }
        try { return Optional.of(future.get(150,TimeUnit.MILLISECONDS)); }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); return Optional.empty(); }
        catch(ExecutionException | TimeoutException | CancellationException unavailable) { return Optional.empty(); }
        finally { if(!future.isDone()) future.cancel(false); }
    }
    @Override public void onDisable() {
        ready=false; if(proxyCommands!=null) proxyCommands.close();
        if(departures!=null) departures.close(); if(teleports!=null) teleports.close(); if(statistics!=null) statistics.close(); if(placeholders!=null) placeholders.unregister();
        Bukkit.getServicesManager().unregisterAll(this); nameTags.close(); onlineNames.clear(); onlinePlayers.clear();
        if(api!=null) { try { api.presence(serverId,List.of()).get(2200,TimeUnit.MILLISECONDS); } catch(Exception ignored) {} api.close(); }
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void preLogin(AsyncPlayerPreLoginEvent event) {
        if(!ready) { event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,DENIED); return; }
        try {
            refreshes.fetch(event.getUniqueId()).get(2100,TimeUnit.MILLISECONDS);
            if(!policies.allows(event.getUniqueId(),serverId,Instant.now())) event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST,DENIED);
        } catch(Exception error) { event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,DENIED); }
    }
    @EventHandler public void join(PlayerJoinEvent event) {
        if(!allowed(event.getPlayer())) event.getPlayer().kick(DENIED);
        else { onlinePlayers.put(event.getPlayer().getUniqueId(),event.getPlayer());
            onlineNames.put(event.getPlayer().getUniqueId(),event.getPlayer().getName());
            Bukkit.getOnlinePlayers().forEach(this::display); }
    }
    @EventHandler public void quit(PlayerQuitEvent event) { UUID id=event.getPlayer().getUniqueId(); onlinePlayers.remove(id,event.getPlayer()); onlineNames.remove(id); nameTags.remove(id); }
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
    private void display(Player player) {
        Policy policy=displayEvents.policy(player.getUniqueId());
        boolean staff=player.hasPermission(DisplayEvents.STAFF);
        if(displaySettings.tab().enabled()) {
            player.playerListName(identityDisplay.render(displaySettings.tab(),player.getName(),policy,Component.empty(),"",staff));
            int order=policy!=null && (policy.administrator() || staff) ? 1 : 0;
            if(player.getPlayerListOrder()!=order) player.setPlayerListOrder(order);
        }
        if(displaySettings.nameplate().enabled()) nameTags.update(player,identityDisplay.nameplate(player.getName(),policy,staff));
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void move(PlayerMoveEvent event) { if(!allowed(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void interact(PlayerInteractEvent event) { if(!allowed(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void breakBlock(BlockBreakEvent event) { if(!allowed(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void placeBlock(BlockPlaceEvent event) { if(!allowed(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void command(PlayerCommandPreprocessEvent event) { if(!allowed(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void inventory(InventoryClickEvent event) { if(event.getWhoClicked() instanceof Player p && !allowed(p)) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void damage(EntityDamageByEntityEvent event) { if(event.getDamager() instanceof Player p && !allowed(p)) event.setCancelled(true); }
}
