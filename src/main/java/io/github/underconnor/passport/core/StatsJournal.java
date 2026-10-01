package io.github.underconnor.passport.core;

import com.google.gson.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;
import java.io.IOException;

/** Durable delta outbox. A batch keeps its ID and exact bytes through retries and restarts. */
public final class StatsJournal {
    public static final List<String> METRICS=List.of("playSeconds","blocksBroken","blocksPlaced","damageTakenMilli","deaths","mobKills","playerKills","distanceCm");
    private static final Set<String> OPTIONAL_METRICS=Set.of("playerKills","distanceCm");
    private final Path file; private final String serverId;
    private JsonObject state;
    public StatsJournal(Path file,String serverId) throws IOException {
        this.file=file; this.serverId=serverId;
        Files.createDirectories(file.toAbsolutePath().getParent());
        if(Files.isSymbolicLink(file)) throw new IOException("Statistics journal cannot be a symlink");
        if(Files.exists(file)) {
            if(!Files.isRegularFile(file) || Files.size(file)>64L*1024*1024) throw new IOException("Statistics journal invalid");
            try { state=JsonParser.parseString(Files.readString(file)).getAsJsonObject(); validate(); }
            catch(RuntimeException error) { throw new IOException("Statistics journal invalid; preserve file for recovery",error); }
        } else {
            state=new JsonObject(); state.addProperty("version",1); state.addProperty("serverId",serverId);
            state.addProperty("capturedSince",Instant.now().toString()); state.add("pending",new JsonArray()); state.add("current",new JsonObject()); save();
        }
    }
    private void validate() {
        if(state.get("version").getAsInt()!=1 || !serverId.equals(state.get("serverId").getAsString())) throw new IllegalArgumentException("journal version/server");
        Instant.parse(state.get("capturedSince").getAsString());
        for(var entry:state.getAsJsonObject("current").entrySet()) { String[] parts=entry.getKey().split(":"); if(parts.length!=2) throw new IllegalArgumentException("key"); UUID.fromString(parts[0]); UUID.fromString(parts[1]); validateCounters(entry.getValue().getAsJsonObject()); }
        for(JsonElement value:state.getAsJsonArray("pending")) {
            JsonObject batch=value.getAsJsonObject(); UUID.fromString(batch.get("id").getAsString());
            if(!serverId.equals(batch.get("serverId").getAsString())) throw new IllegalArgumentException("server");
            for(JsonElement record:batch.getAsJsonArray("records")) { UUID.fromString(record.getAsJsonObject().get("minecraftUuid").getAsString()); UUID.fromString(record.getAsJsonObject().get("epoch").getAsString()); validateCounters(record.getAsJsonObject()); }
        }
    }
    private static void validateCounters(JsonObject counters) {
        for(String key:METRICS) {
            // Old pending batches are immutable: retain their original payload and receipt hash.
            if(!counters.has(key) && OPTIONAL_METRICS.contains(key)) continue;
            if(counters.get(key).getAsLong()<0 || counters.get(key).getAsLong()>Integer.MAX_VALUE) throw new IllegalArgumentException("counter");
        }
    }
    public synchronized void add(UUID uuid,UUID epoch,String metric,long amount) {
        if(epoch==null || !METRICS.contains(metric) || amount<0 || amount>Integer.MAX_VALUE) throw new IllegalArgumentException("counter");
        if(amount==0) return;
        String id=uuid+":"+epoch; JsonObject current=state.getAsJsonObject("current"); JsonObject counters=current.getAsJsonObject(id);
        if(counters==null) { counters=new JsonObject(); for(String key:METRICS) counters.addProperty(key,0); current.add(id,counters); }
        long next=Math.addExact(counters.has(metric) ? counters.get(metric).getAsLong() : 0,amount);
        if(next>Integer.MAX_VALUE) throw new IllegalStateException("Statistics batch counter exceeds maximum");
        counters.addProperty(metric,next);
    }
    /** Persist current counters, moving up to 50 players to one immutable batch. */
    public synchronized void checkpoint() throws IOException {
        JsonObject current=state.getAsJsonObject("current"); JsonArray pending=state.getAsJsonArray("pending");
        if(pending.size()>=10000) throw new IOException("Statistics outbox full; repair API before continuing collection");
        if(current.size()>0 && pending.isEmpty()) {
            JsonArray records=new JsonArray(); List<String> ids=new ArrayList<>(current.keySet()); Collections.sort(ids);
            Set<String> selectedPlayers=new HashSet<>();
            for(String id:ids) {
                if(records.size()>=50) break; String[] key=id.split(":");
                if(!selectedPlayers.add(key[0])) continue;
                JsonObject record=current.remove(id).getAsJsonObject(); record.addProperty("minecraftUuid",key[0]); record.addProperty("epoch",key[1]); records.add(record);
            }
            JsonObject batch=new JsonObject(); batch.addProperty("id",UUID.randomUUID().toString()); batch.addProperty("serverId",serverId);
            batch.add("records",records); pending.add(batch);
        }
        save();
    }
    public synchronized Optional<JsonObject> first() { return state.getAsJsonArray("pending").isEmpty() ? Optional.empty() : Optional.of(state.getAsJsonArray("pending").get(0).getAsJsonObject().deepCopy()); }
    public synchronized void acknowledge(String id) throws IOException {
        JsonArray pending=state.getAsJsonArray("pending");
        if(!pending.isEmpty() && pending.get(0).getAsJsonObject().get("id").getAsString().equals(id)) { pending.remove(0); save(); }
    }
    private void save() throws IOException {
        byte[] bytes=state.toString().getBytes(StandardCharsets.UTF_8); Path temp=file.resolveSibling(file.getFileName()+".tmp");
        if(Files.isSymbolicLink(temp)) throw new IOException("Statistics journal temp cannot be a symlink");
        try(FileChannel out=FileChannel.open(temp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            try { Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString("rw-------")); } catch(UnsupportedOperationException ignored) {}
            ByteBuffer buffer=ByteBuffer.wrap(bytes); while(buffer.hasRemaining()) out.write(buffer); out.force(true);
        }
        Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        try(FileChannel directory=FileChannel.open(file.toAbsolutePath().getParent(),StandardOpenOption.READ)) { directory.force(true); }
    }
}
