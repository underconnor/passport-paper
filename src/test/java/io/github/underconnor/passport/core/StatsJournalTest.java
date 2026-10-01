package io.github.underconnor.passport.core;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class StatsJournalTest {
    @TempDir Path directory;
    @Test void restartRetriesSameBatchAndCountersDoNotJoinAnotherEpoch() throws Exception {
        Path file=directory.resolve("statistics.json"); UUID player=UUID.randomUUID(),oldEpoch=UUID.randomUUID(),newEpoch=UUID.randomUUID();
        StatsJournal journal=new StatsJournal(file,"lobby"); journal.add(player,oldEpoch,"blocksBroken",3); journal.checkpoint();
        String first=journal.first().orElseThrow().toString();
        journal.add(player,newEpoch,"blocksBroken",2); journal.checkpoint();
        StatsJournal restarted=new StatsJournal(file,"lobby"); assertEquals(first,restarted.first().orElseThrow().toString());
        String id=restarted.first().orElseThrow().get("id").getAsString(); restarted.acknowledge(UUID.randomUUID().toString()); assertEquals(first,restarted.first().orElseThrow().toString());
        restarted.acknowledge(id); restarted.checkpoint(); var record=restarted.first().orElseThrow().getAsJsonArray("records").get(0).getAsJsonObject();
        assertEquals(newEpoch.toString(),record.get("epoch").getAsString()); assertEquals(2,record.get("blocksBroken").getAsInt());
        restarted.acknowledge(restarted.first().orElseThrow().get("id").getAsString()); assertTrue(new StatsJournal(file,"lobby").first().isEmpty());
    }
    @Test void malformedAndWrongServerStateIsPreservedInsteadOfResettingCounters() throws Exception {
        Path file=directory.resolve("statistics.json"); new StatsJournal(file,"lobby");
        assertThrows(java.io.IOException.class,() -> new StatsJournal(file,"build"));
        Files.writeString(file,"malformed"); assertThrows(java.io.IOException.class,() -> new StatsJournal(file,"lobby")); assertEquals("malformed",Files.readString(file));
    }
    @Test void journalRejectsNegativeAndUnknownCountersAndSymlinks() throws Exception {
        StatsJournal journal=new StatsJournal(directory.resolve("safe.json"),"lobby");
        assertThrows(IllegalArgumentException.class,() -> journal.add(UUID.randomUUID(),UUID.randomUUID(),"deaths",-1));
        assertThrows(IllegalArgumentException.class,() -> journal.add(UUID.randomUUID(),UUID.randomUUID(),"studentNumber",1));
        Path link=directory.resolve("linked.json"); Files.createSymbolicLink(link,directory.resolve("safe.json"));
        assertThrows(java.io.IOException.class,() -> new StatsJournal(link,"lobby"));
    }
    @Test void sameUuidDifferentEpochsUseSeparateBatches() throws Exception {
        UUID player=UUID.randomUUID(); StatsJournal journal=new StatsJournal(directory.resolve("epoch.json"),"lobby");
        journal.add(player,UUID.randomUUID(),"playSeconds",5); journal.add(player,UUID.randomUUID(),"playSeconds",9); journal.checkpoint();
        assertEquals(1,journal.first().orElseThrow().getAsJsonArray("records").size());
        String firstEpoch=journal.first().orElseThrow().getAsJsonArray("records").get(0).getAsJsonObject().get("epoch").getAsString();
        journal.acknowledge(journal.first().orElseThrow().get("id").getAsString()); journal.checkpoint();
        assertEquals(1,journal.first().orElseThrow().getAsJsonArray("records").size());
        assertNotEquals(firstEpoch,journal.first().orElseThrow().getAsJsonArray("records").get(0).getAsJsonObject().get("epoch").getAsString());
    }
}
