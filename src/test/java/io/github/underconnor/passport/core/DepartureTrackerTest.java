package io.github.underconnor.passport.core;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DepartureTrackerTest {
    final DepartureTracker tracker=new DepartureTracker();
    final UUID actor=UUID.randomUUID(); final Instant now=Instant.now();
    DepartureMessage start() { var start=DepartureMessage.begin(actor,"lobby",now); tracker.joined(actor); tracker.begin(start); return start; }
    @Test void successBeforeQuitAndSuccessAfterQuitBothProduceOnlyOneTransfer() {
        for(boolean before:List.of(false,true)) {
            var visit=start(); var result=new ArrayList<Boolean>();
            if(before) tracker.transferred(visit.transferred(now));
            tracker.quit(actor,result::add,0);
            tracker.transferred(visit.transferred(now)); tracker.tick(2_000_000_000L);
            assertEquals(List.of(true),result);
        }
    }
    @Test void realDisconnectOrFailedTransferFallsBackToQuitAfterOneSecond() {
        start(); var result=new ArrayList<Boolean>(); tracker.quit(actor,result::add,0);
        tracker.tick(999_999_999L); assertTrue(result.isEmpty()); tracker.tick(1_000_000_000L);
        assertEquals(List.of(false),result); tracker.tick(2_000_000_000L); assertEquals(1,result.size());
    }
    @Test void expiredCallbackIgnoresLateReplayAndTheNextVisitHasAnIndependentNonce() {
        var old=start(); var first=new ArrayList<Boolean>(); tracker.quit(actor,first::add,0); tracker.tick(1_000_000_000L);
        var fresh=start(); tracker.transferred(old.transferred(now)); var second=new ArrayList<Boolean>(); tracker.quit(actor,second::add,2_000_000_000L);
        tracker.transferred(fresh.transferred(now)); tracker.transferred(fresh.transferred(now)); tracker.tick(5_000_000_000L);
        assertEquals(List.of(false),first); assertEquals(List.of(true),second);
    }
    @Test void fastReturnKeepsTheOldPendingDepartureSeparateFromTheNewConnection() {
        var old=start(); var first=new ArrayList<Boolean>(); tracker.quit(actor,first::add,0);
        start(); var second=new ArrayList<Boolean>(); tracker.transferred(old.transferred(now)); tracker.quit(actor,second::add,0); tracker.tick(1_000_000_000L);
        assertEquals(List.of(true),first); assertEquals(List.of(false),second);
    }
    @Test void unknownVisitAndWrongActorCannotClaimAnotherPlayersDeparture() {
        var unknown=new ArrayList<Boolean>(); tracker.quit(actor,unknown::add,0); assertEquals(List.of(false),unknown);
        var visit=start(); var result=new ArrayList<Boolean>(); tracker.quit(actor,result::add,0);
        tracker.transferred(new DepartureMessage("TRANSFER",visit.connectionId(),UUID.randomUUID(),"lobby",visit.expiresAt()));
        tracker.tick(1_000_000_000L); assertEquals(List.of(false),result);
    }
    @Test void stoppingDiscardsPendingBroadcasts() {
        start(); var result=new ArrayList<Boolean>(); tracker.quit(actor,result::add,0); tracker.clear(); tracker.tick(2_000_000_000L); assertTrue(result.isEmpty());
    }
}
