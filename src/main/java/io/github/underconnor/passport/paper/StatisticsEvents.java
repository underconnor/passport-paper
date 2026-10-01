package io.github.underconnor.passport.paper;

import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;

/** Successful gameplay events only; collection authorization stays in the recorder. */
abstract class StatisticsEvents implements Listener {
    protected abstract void record(Player player,String metric,long value);
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void broken(BlockBreakEvent event) {
        if(!event.isCancelled()) record(event.getPlayer(),"blocksBroken",1);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void placed(BlockPlaceEvent event) {
        if(!event.isCancelled() && event.canBuild()) record(event.getPlayer(),"blocksPlaced",event instanceof BlockMultiPlaceEvent multi ? multi.getReplacedBlockStates().size() : 1);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void damage(EntityDamageEvent event) {
        if(!event.isCancelled() && event.getEntity() instanceof Player player && Double.isFinite(event.getFinalDamage()))
            record(player,"damageTakenMilli",Math.round(Math.max(0,event.getFinalDamage())*1000));
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void death(PlayerDeathEvent event) {
        if(event.isCancelled()) return;
        record(event.getEntity(),"deaths",1);
        Player killer=event.getEntity().getKiller();
        if(killer!=null && !killer.getUniqueId().equals(event.getEntity().getUniqueId())) record(killer,"playerKills",1);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void killed(EntityDeathEvent event) {
        if(!event.isCancelled() && event.getEntity() instanceof Mob && event.getEntity().getKiller()!=null)
            record(event.getEntity().getKiller(),"mobKills",1);
    }
}
