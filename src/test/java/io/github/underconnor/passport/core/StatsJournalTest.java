package io.github.underconnor.passport.core;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class StatsJournalTest {
    @TempDir Path directory;
    @Test void oldSixCounterPendingBatchRemainsIdenticalWhileNewMetricsSurviveRestart() throws Exception {
        Path file=directory.resolve("legacy.json"); UUID player=UUID.randomUUID(),epoch=UUID.randomUUID();
        StatsJournal original=new StatsJournal(file,"lobby"); original.add(player,epoch,"mobKills",2); original.checkpoint();
        var state=com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        var oldBatch=state.getAsJsonArray("pending").get(0).getAsJsonObject();
        var oldRecord=oldBatch.getAsJsonArray("records").get(0).getAsJsonObject(); oldRecord.remove("playerKills"); oldRecord.remove("distanceCm");
        var current=new com.google.gson.JsonObject();
        for(String key:StatsJournal.METRICS) if(!key.equals("playerKills") && !key.equals("distanceCm")) current.addProperty(key,0);
        state.getAsJsonObject("current").add(player+":"+epoch,current); Files.writeString(file,state.toString());
        StatsJournal upgraded=new StatsJournal(file,"lobby");
        upgraded.add(player,epoch,"playerKills",1); upgraded.add(player,epoch,"distanceCm",1250); upgraded.checkpoint();
        assertEquals(oldBatch.toString(),upgraded.first().orElseThrow().toString());
        upgraded.acknowledge(oldBatch.get("id").getAsString()); upgraded.checkpoint();
        var record=new StatsJournal(file,"lobby").first().orElseThrow().getAsJsonArray("records").get(0).getAsJsonObject();
        assertEquals(1,record.get("playerKills").getAsInt()); assertEquals(1250,record.get("distanceCm").getAsInt()); assertEquals(0,record.get("mobKills").getAsInt());
    }
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
