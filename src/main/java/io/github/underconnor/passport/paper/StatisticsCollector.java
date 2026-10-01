package io.github.underconnor.passport.paper;
import io.github.underconnor.passport.core.*;
import org.bukkit.Statistic;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
/** Events run on Paper's main thread. Persistence and network requests use one background worker. */
final class StatisticsCollector implements Listener,AutoCloseable {
    private final JavaPlugin plugin; private final ApiClient api; private final Function<Player,UUID> epoch;
    private final StatsJournal journal;
    private final MovementDeltas movement=new MovementDeltas();
    // Mutually selected vanilla travel modes; FALL is excluded to avoid counting airborne travel twice.
    private static final Statistic[] TRAVEL={Statistic.WALK_ONE_CM,Statistic.SPRINT_ONE_CM,Statistic.CROUCH_ONE_CM,
        Statistic.SWIM_ONE_CM,Statistic.WALK_ON_WATER_ONE_CM,Statistic.WALK_UNDER_WATER_ONE_CM,Statistic.CLIMB_ONE_CM,
        Statistic.FLY_ONE_CM,Statistic.AVIATE_ONE_CM,Statistic.MINECART_ONE_CM,Statistic.BOAT_ONE_CM,Statistic.PIG_ONE_CM,
        Statistic.HORSE_ONE_CM,Statistic.STRIDER_ONE_CM,Statistic.HAPPY_GHAST_ONE_CM,Statistic.NAUTILUS_ONE_CM};
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r -> { Thread thread=new Thread(r,"passport-statistics"); thread.setDaemon(true); return thread; });
    private record Increment(UUID uuid,UUID epoch,String metric,long value) {}
    private final ConcurrentLinkedQueue<Increment> incoming=new ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicInteger queued=new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean healthy=true,closed;
    private boolean apiHealthy=true;
    StatisticsCollector(JavaPlugin plugin,ApiClient api,String serverId,Function<Player,UUID> epoch) throws java.io.IOException {
        this.plugin=plugin; this.api=api; this.epoch=epoch;
        journal=new StatsJournal(plugin.getDataFolder().toPath().resolve("statistics.json"),serverId);
        worker.scheduleWithFixedDelay(this::flush,1,1,TimeUnit.SECONDS);
    }
    private void record(Player player,String metric,long value) {
        record(player,epoch.apply(player),metric,value);
    }
    private void record(Player player,UUID generation,String metric,long value) {
        if(closed || !healthy || generation==null || value<=0) return;
        if(queued.incrementAndGet()>100000) { queued.decrementAndGet(); healthy=false; plugin.getLogger().severe("Passport statistics memory buffer full; collection paused until storage recovers."); return; }
        incoming.add(new Increment(player.getUniqueId(),generation,metric,Math.min(value,Integer.MAX_VALUE)));
    }
    private void drain() { Increment item; while((item=incoming.poll())!=null) { queued.decrementAndGet(); journal.add(item.uuid(),item.epoch(),item.metric(),item.value()); } }
    private void sampleDistance(Player player) {
        UUID generation=healthy && !closed ? epoch.apply(player) : null;
        if(generation==null) { movement.forget(player.getUniqueId()); return; }
        int[] values=new int[TRAVEL.length];
        for(int i=0;i<TRAVEL.length;i++) values[i]=player.getStatistic(TRAVEL[i]);
        record(player,generation,"distanceCm",movement.sample(player.getUniqueId(),generation,values));
    }
    void second(Collection<? extends Player> players) { for(Player player:players) { record(player,"playSeconds",1); sampleDistance(player); } }
    @EventHandler(priority=EventPriority.MONITOR) public void join(PlayerJoinEvent event) { movement.forget(event.getPlayer().getUniqueId()); sampleDistance(event.getPlayer()); }
    @EventHandler(priority=EventPriority.MONITOR) public void quit(PlayerQuitEvent event) { sampleDistance(event.getPlayer()); movement.forget(event.getPlayer().getUniqueId()); }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void broken(BlockBreakEvent event) { record(event.getPlayer(),"blocksBroken",1); }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void placed(BlockPlaceEvent event) { record(event.getPlayer(),"blocksPlaced",event instanceof BlockMultiPlaceEvent multi ? multi.getReplacedBlockStates().size() : 1); }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void damage(EntityDamageEvent event) {
        if(event.getEntity() instanceof Player player && Double.isFinite(event.getFinalDamage())) record(player,"damageTakenMilli",Math.round(Math.max(0,event.getFinalDamage())*1000));
    }
    @EventHandler(priority=EventPriority.MONITOR) public void death(PlayerDeathEvent event) {
        record(event.getEntity(),"deaths",1);
        Player killer=event.getEntity().getKiller();
        if(killer!=null && !killer.getUniqueId().equals(event.getEntity().getUniqueId())) record(killer,"playerKills",1);
    }
    @EventHandler(priority=EventPriority.MONITOR) public void killed(EntityDeathEvent event) {
        if(event.getEntity() instanceof Mob && event.getEntity().getKiller()!=null) record(event.getEntity().getKiller(),"mobKills",1);
    }
    private void flush() {
        try {
            drain(); journal.checkpoint();
            if(!healthy) { healthy=true; plugin.getLogger().info("Passport statistics storage recovered"); }
            // Bounded catch-up: at most 10 batches per pass.
            for(int i=0;i<10;i++) {
                var next=journal.first(); if(next.isEmpty()) break;
                api.statistics(next.get()).get(2200,TimeUnit.MILLISECONDS);
                journal.acknowledge(next.get().get("id").getAsString());
                journal.checkpoint();
            }
            if(!apiHealthy) { apiHealthy=true; plugin.getLogger().info("Passport statistics API recovered"); }
        } catch(java.io.IOException error) {
            if(healthy) plugin.getLogger().severe("Passport statistics storage failed; collection paused. Preserve statistics.json and restore writable storage."); healthy=false;
        } catch(Exception error) { if(apiHealthy && !closed) plugin.getLogger().warning("Passport statistics API unavailable; durable batches retained for retry"); apiHealthy=false; }
    }
    @Override public void close() {
        plugin.getServer().getOnlinePlayers().forEach(this::sampleDistance); movement.clear();
        closed=true; worker.shutdown();
        try { if(!worker.awaitTermination(5,TimeUnit.SECONDS)) worker.shutdownNow(); drain(); journal.checkpoint(); }
        catch(Exception error) { plugin.getLogger().severe("Passport statistics shutdown checkpoint failed; preserve statistics.json for recovery."); }
    }
}
