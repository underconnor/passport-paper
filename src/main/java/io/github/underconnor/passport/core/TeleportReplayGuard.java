package io.github.underconnor.passport.core;
import java.time.Instant;
import java.util.*;
/** An unexpired request is never evicted to make room for another request. */
public final class TeleportReplayGuard {
    private final Map<UUID,Long> seen=new HashMap<>();
    private final int capacity;
    public TeleportReplayGuard(int capacity) { if(capacity<1) throw new IllegalArgumentException("capacity"); this.capacity=capacity; }
    public synchronized boolean claim(TeleportMessage request,Instant now) {
        seen.values().removeIf(expiry -> expiry<=now.toEpochMilli());
        if(!request.kind().equals("REQUEST") || !request.valid(now) || seen.containsKey(request.requestId()) || seen.size()>=capacity) return false;
        seen.put(request.requestId(),request.expiresAt()); return true;
    }
    public synchronized void clear() { seen.clear(); }
}
