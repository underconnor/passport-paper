package io.github.underconnor.passport.core;
import java.util.*;

/** Counts actual vanilla play ticks, retaining fractional seconds within one authorized session. */
public final class PlaytimeDeltas {
    private record Baseline(UUID epoch,int ticks,int remainder) {}
    private final Map<UUID,Baseline> baselines=new HashMap<>();
    public long sample(UUID player,UUID epoch,int ticks) {
        if(epoch==null || ticks<0) { forget(player); return 0; }
        Baseline previous=baselines.get(player);
        if(previous==null || !previous.epoch().equals(epoch) || ticks<previous.ticks()) {
            baselines.put(player,new Baseline(epoch,ticks,0)); return 0;
        }
        long elapsed=(long)ticks-previous.ticks()+previous.remainder();
        baselines.put(player,new Baseline(epoch,ticks,(int)(elapsed%20)));
        return elapsed/20;
    }
    public void forget(UUID player) { baselines.remove(player); }
    public void clear() { baselines.clear(); }
}
