package io.github.underconnor.passport.core;

import java.util.*;
import java.util.function.Consumer;

/** Main-thread state; a transfer can arrive before or after Paper's quit event. */
public final class DepartureTracker {
    private record Visit(UUID nonce,boolean transferred) {}
    private record Pending(UUID actor,Consumer<Boolean> completed,long deadline) {}
    private final Map<UUID,Visit> visits=new HashMap<>();
    private final Map<UUID,Pending> pending=new HashMap<>();
    public void joined(UUID actor) { visits.remove(actor); }
    public void begin(DepartureMessage message) {
        if(message.kind().equals("BEGIN")) visits.putIfAbsent(message.actor(),new Visit(message.connectionId(),false));
    }
    public void transferred(DepartureMessage message) {
        if(!message.kind().equals("TRANSFER")) return;
        Visit visit=visits.get(message.actor());
        if(visit!=null && visit.nonce().equals(message.connectionId())) visits.put(message.actor(),new Visit(visit.nonce(),true));
        Pending quit=pending.get(message.connectionId());
        if(quit!=null && quit.actor().equals(message.actor()) && pending.remove(message.connectionId(),quit)) quit.completed().accept(true);
    }
    public void quit(UUID actor,Consumer<Boolean> completed,long monotonicNow) {
        Visit visit=visits.remove(actor);
        if(visit==null || visit.transferred()) { completed.accept(visit!=null); return; }
        pending.put(visit.nonce(),new Pending(actor,completed,monotonicNow+1_000_000_000L));
    }
    public void tick(long monotonicNow) {
        List<Pending> expired=new ArrayList<>();
        pending.entrySet().removeIf(entry -> { if(monotonicNow-entry.getValue().deadline()>=0) { expired.add(entry.getValue()); return true; } return false; });
        expired.forEach(value -> value.completed().accept(false));
    }
    public void clear() { visits.clear(); pending.clear(); }
}
