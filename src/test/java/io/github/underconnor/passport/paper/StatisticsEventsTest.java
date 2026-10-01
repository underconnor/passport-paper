package io.github.underconnor.passport.paper;
import org.bukkit.block.*;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.inventory.EquipmentSlot;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StatisticsEventsTest {
    record Count(UUID player,String metric,long amount) {}
    private final List<Count> counts=new ArrayList<>();
    private final StatisticsEvents events=new StatisticsEvents() {
        @Override protected void record(Player player,String metric,long value) { if(value>0) counts.add(new Count(player.getUniqueId(),metric,value)); }
    };
    @SuppressWarnings("unchecked") static <T> T stub(Class<T> type,Map<String,Object> values) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args) -> {
            if(method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if(method.getName().equals("equals")) return proxy==args[0];
            return values.get(method.getName());
        });
    }
    private Player player() { return stub(Player.class,Map.of("getUniqueId",UUID.randomUUID())); }
    private final DamageSource source=stub(DamageSource.class,Map.of());
    @Test void cancelledDeathsNeverCountAndPlayerKillsAreNotMobKills() {
        Player killer=player(),victim=stub(Player.class,Map.of("getUniqueId",UUID.randomUUID(),"getKiller",killer));
        PlayerDeathEvent event=new PlayerDeathEvent(victim,source,new ArrayList<>(),0,Component.empty(),true);
        event.setCancelled(true); events.death(event); events.killed(event); assertTrue(counts.isEmpty());
        event.setCancelled(false); events.death(event); events.killed(event);
        assertEquals(List.of(new Count(victim.getUniqueId(),"deaths",1),new Count(killer.getUniqueId(),"playerKills",1)),counts);
    }
    @Test void suicideAndEnvironmentalDeathDoNotCountPlayerKillAndCancelledMobDeathIsExcluded() {
        Player victim=player(),selfKiller=stub(Player.class,Map.of("getUniqueId",victim.getUniqueId(),"getKiller",victim));
        events.death(new PlayerDeathEvent(selfKiller,source,new ArrayList<>(),0,Component.empty(),true));
        events.death(new PlayerDeathEvent(victim,source,new ArrayList<>(),0,Component.empty(),true));
        assertEquals(2,counts.size()); assertTrue(counts.stream().allMatch(c -> c.metric().equals("deaths")));
        counts.clear(); Mob mob=stub(Mob.class,Map.of("getKiller",victim));
        EntityDeathEvent killed=new EntityDeathEvent(mob,source,new ArrayList<>());
        killed.setCancelled(true); events.killed(killed); assertTrue(counts.isEmpty());
        killed.setCancelled(false); events.killed(killed);
        assertEquals(List.of(new Count(victim.getUniqueId(),"mobKills",1)),counts);
    }
    @Test void buildDeniedAndCancelledBlocksAreExcludedAndMultiPlaceCountsEachBlockOnce() {
        Player player=player(); Block block=stub(Block.class,Map.of()); BlockState state=stub(BlockState.class,Map.of("getBlock",block));
        BlockBreakEvent broken=new BlockBreakEvent(block,player); broken.setCancelled(true); events.broken(broken); assertTrue(counts.isEmpty());
        broken.setCancelled(false); events.broken(broken);
        BlockPlaceEvent placed=new BlockPlaceEvent(block,state,block,null,player,false,EquipmentSlot.HAND);
        events.placed(placed); assertEquals(1,counts.size());
        placed.setBuild(true); placed.setCancelled(true); events.placed(placed); assertEquals(1,counts.size());
        BlockMultiPlaceEvent multi=new BlockMultiPlaceEvent(List.of(state,state),block,null,player,true,EquipmentSlot.HAND); events.placed(multi);
        assertEquals(List.of(new Count(player.getUniqueId(),"blocksBroken",1),new Count(player.getUniqueId(),"blocksPlaced",2)),counts);
    }
    @Test void finalDamageUsesMilliDamagePointsAndExcludesCancelledAndZeroDamage() {
        Player player=player(); EntityDamageEvent damage=new EntityDamageEvent(player,EntityDamageEvent.DamageCause.CUSTOM,source,2.3456);
        damage.setCancelled(true); events.damage(damage); assertTrue(counts.isEmpty());
        damage.setCancelled(false); events.damage(damage);
        assertEquals(List.of(new Count(player.getUniqueId(),"damageTakenMilli",2346)),counts);
        events.damage(new EntityDamageEvent(player,EntityDamageEvent.DamageCause.CUSTOM,source,0)); assertEquals(1,counts.size());
        var modifiers=new EnumMap<EntityDamageEvent.DamageModifier,Double>(EntityDamageEvent.DamageModifier.class);
        modifiers.put(EntityDamageEvent.DamageModifier.BASE,10.0); modifiers.put(EntityDamageEvent.DamageModifier.ARMOR,-4.0); modifiers.put(EntityDamageEvent.DamageModifier.ABSORPTION,-2.0);
        var functions=new EnumMap<EntityDamageEvent.DamageModifier,com.google.common.base.Function<Double,Double>>(EntityDamageEvent.DamageModifier.class);
        modifiers.keySet().forEach(key -> functions.put(key,unused -> 0.0));
        events.damage(new EntityDamageEvent(player,EntityDamageEvent.DamageCause.CUSTOM,source,modifiers,functions));
        assertEquals(new Count(player.getUniqueId(),"damageTakenMilli",4000),counts.getLast());
    }
}
