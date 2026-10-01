package io.github.underconnor.passport.paper;
import io.github.underconnor.passport.core.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
/** Events run on Paper's main thread. Persistence and network requests use one background worker. */
final class StatisticsCollector implements Listener,AutoCloseable {
    private final JavaPlugin plugin; private final ApiClient api; private final Function<Player,UUID> epoch;
    private final StatsJournal journal;
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
        UUID generation=epoch.apply(player); if(closed || !healthy || generation==null || value<=0) return;
        if(queued.incrementAndGet()>100000) { queued.decrementAndGet(); healthy=false; plugin.getLogger().severe("Passport statistics memory buffer full; collection paused until storage recovers."); return; }
        incoming.add(new Increment(player.getUniqueId(),generation,metric,Math.min(value,Integer.MAX_VALUE)));
    }
    private void drain() { Increment item; while((item=incoming.poll())!=null) { queued.decrementAndGet(); journal.add(item.uuid(),item.epoch(),item.metric(),item.value()); } }
    void second(Collection<? extends Player> players) { for(Player player:players) record(player,"playSeconds",1); }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void broken(BlockBreakEvent event) { record(event.getPlayer(),"blocksBroken",1); }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void placed(BlockPlaceEvent event) { record(event.getPlayer(),"blocksPlaced",event instanceof BlockMultiPlaceEvent multi ? multi.getReplacedBlockStates().size() : 1); }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void damage(EntityDamageEvent event) {
        if(event.getEntity() instanceof Player player && Double.isFinite(event.getFinalDamage())) record(player,"damageTakenMilli",Math.round(Math.max(0,event.getFinalDamage())*1000));
    }
    @EventHandler(priority=EventPriority.MONITOR) public void death(PlayerDeathEvent event) { record(event.getEntity(),"deaths",1); }
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
        closed=true; worker.shutdown();
        try { if(!worker.awaitTermination(5,TimeUnit.SECONDS)) worker.shutdownNow(); drain(); journal.checkpoint(); }
        catch(Exception error) { plugin.getLogger().severe("Passport statistics shutdown checkpoint failed; preserve statistics.json for recovery."); }
    }
}
