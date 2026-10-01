package io.github.underconnor.passport.core;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class TeleportReplayGuardTest {
    @Test void capacityCannotEvictLiveNonceAndExpiredSlotsCanBeReused() {
        Instant now=Instant.parse("2026-10-01T00:00:00Z");
        var first=TeleportMessage.request(UUID.randomUUID(),UUID.randomUUID(),"lobby",now);
        var second=TeleportMessage.request(UUID.randomUUID(),UUID.randomUUID(),"lobby",now.plusSeconds(1));
        var guard=new TeleportReplayGuard(1);
        assertTrue(guard.claim(first,now)); assertFalse(guard.claim(first,now)); assertFalse(guard.claim(second,now.plusSeconds(1)));
        assertFalse(guard.claim(first,now.plusSeconds(15))); assertTrue(guard.claim(second,now.plusSeconds(15)));
        assertFalse(guard.claim(second.reply("ok"),now.plusSeconds(15)));
    }
}
