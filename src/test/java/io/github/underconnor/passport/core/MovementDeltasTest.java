package io.github.underconnor.passport.core;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class MovementDeltasTest {
    @Test void existingLifetimeHistoryIsExcludedAndEachModeAddsOnlyItsDelta() {
        MovementDeltas distance=new MovementDeltas(); UUID player=UUID.randomUUID(),epoch=UUID.randomUUID();
        int[] values={100000,200000,50}; assertEquals(0,distance.sample(player,epoch,values));
        values[0]+=100; values[1]+=500;
        assertEquals(600,distance.sample(player,epoch,values));
        assertEquals(0,distance.sample(player,epoch,values));
    }
    @Test void reconnectEpochChangeAndConsentGapDoNotImportPriorMovement() {
        MovementDeltas distance=new MovementDeltas(); UUID player=UUID.randomUUID(),epoch=UUID.randomUUID();
        distance.sample(player,epoch,new int[]{100}); distance.forget(player);
        assertEquals(0,distance.sample(player,epoch,new int[]{500}));
        UUID next=UUID.randomUUID(); assertEquals(0,distance.sample(player,next,new int[]{700}));
        assertEquals(10,distance.sample(player,next,new int[]{710}));
        assertEquals(0,distance.sample(player,null,new int[]{800}));
        assertEquals(0,distance.sample(player,next,new int[]{900}));
    }
    @Test void resetModeCannotSubtractOrImportHistoryAndOtherPlayersRemainSeparate() {
        MovementDeltas distance=new MovementDeltas(); UUID player=UUID.randomUUID(),other=UUID.randomUUID(),epoch=UUID.randomUUID();
        distance.sample(player,epoch,new int[]{Integer.MAX_VALUE,100});
        assertEquals(20,distance.sample(player,epoch,new int[]{0,120}));
        assertEquals(5,distance.sample(player,epoch,new int[]{5,120}));
        assertEquals(0,distance.sample(other,epoch,new int[]{500,900}));
        distance.clear(); assertEquals(0,distance.sample(player,epoch,new int[]{1000,2000}));
    }
}
