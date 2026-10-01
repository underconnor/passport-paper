package io.github.underconnor.passport.core;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class PlaytimeDeltasTest {
    @Test void subSecondSessionDoesNotBecomeAFullSecondAndRemainderCarriesUntilFinalSample() {
        PlaytimeDeltas time=new PlaytimeDeltas(); UUID player=UUID.randomUUID(),epoch=UUID.randomUUID();
        assertEquals(0,time.sample(player,epoch,10000));
        assertEquals(0,time.sample(player,epoch,10001));
        assertEquals(1,time.sample(player,epoch,10021));
        assertEquals(1,time.sample(player,epoch,10040));
        assertEquals(0,time.sample(player,epoch,10040));
        assertEquals(3,time.sample(player,epoch,10100));
    }
    @Test void consentGapResetAndReconnectDoNotRestoreOldOrLifetimeTime() {
        PlaytimeDeltas time=new PlaytimeDeltas(); UUID player=UUID.randomUUID(),epoch=UUID.randomUUID();
        time.sample(player,epoch,100); assertEquals(0,time.sample(player,null,200));
        assertEquals(0,time.sample(player,epoch,300)); assertEquals(1,time.sample(player,epoch,320));
        assertEquals(0,time.sample(player,UUID.randomUUID(),400));
        assertEquals(0,time.sample(player,epoch,0));
        time.forget(player); assertEquals(0,time.sample(player,epoch,99999));
    }
}
