package io.github.underconnor.passport.core;

import java.util.*;

/** Main-thread, session-only baselines. Lifetime counters never become historical Passport data. */
public final class MovementDeltas {
    private record Baseline(UUID epoch, int[] values) {}
    private final Map<UUID,Baseline> baselines=new HashMap<>();

    public long sample(UUID player,UUID epoch,int[] values) {
        if(epoch==null) { forget(player); return 0; }
        for(int value:values) if(value<0) { forget(player); return 0; }
        Baseline previous=baselines.put(player,new Baseline(epoch,values.clone()));
        if(previous==null || !previous.epoch().equals(epoch) || previous.values().length!=values.length) return 0;
        long delta=0;
        for(int i=0;i<values.length;i++) {
            // A reset/wrap is a new baseline for that category, never a lifetime increment.
            if(values[i]>=previous.values()[i]) delta+=(long)values[i]-previous.values()[i];
        }
        return delta;
    }
    public void forget(UUID player) { baselines.remove(player); }
    public void clear() { baselines.clear(); }
}
