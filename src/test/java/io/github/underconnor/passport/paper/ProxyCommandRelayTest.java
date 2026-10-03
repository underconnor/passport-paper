package io.github.underconnor.passport.paper;

import io.github.underconnor.passport.core.ProxyCommandMessage;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProxyCommandRelayTest {
    private static final String SECRET="test-shared-proxy-command-secret-12345678";
    private static final Instant NOW=Instant.parse("2026-10-04T04:00:00Z");
    private final UUID uuid=UUID.randomUUID();
    private final Map<UUID,Player> online=new HashMap<>();
    private final List<Component> messages=new ArrayList<>();
    private final List<byte[]> payloads=new ArrayList<>();
    private final List<Player> carriers=new ArrayList<>();
    private boolean ready=true,allowed=true,failSend;
    private int unregistered;
    private long nanos=1_000_000_000L;
    private final Player player=player(uuid,false,true);

    Player player(UUID id,boolean npc,boolean connected) {
        return (Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(object,method,args) -> switch(method.getName()) {
            case "getUniqueId" -> id;
            case "hasMetadata" -> { assertEquals("NPC",args[0]); yield npc; }
            case "isOnline" -> connected;
            case "sendMessage" -> { if(args[args.length-1] instanceof Component component) messages.add(component); yield null; }
            case "hashCode" -> System.identityHashCode(object); case "equals" -> object==args[0];
            default -> throw new AssertionError("Unexpected player call: "+method.getName());
        });
    }

    ProxyCommandRelay relay() {
        online.put(uuid,player);
        return new ProxyCommandRelay("ssu_lobby",SECRET,() -> ready,p -> allowed,online::get,
            (carrier,payload) -> { if(failSend) throw new IllegalStateException("channel unavailable"); carriers.add(carrier); payloads.add(payload); },
            Clock.fixed(NOW,ZoneOffset.UTC),() -> nanos,() -> unregistered++);
    }

    void run(ProxyCommandRelay relay,CommandSender sender,String label,String... args) { assertTrue(relay.onCommand(sender,null,label,args)); }

    @Test void citizensPlayerDispatchSignsTheRealOnlineClickerWithoutRequestingOp() {
        var relay=relay(); run(relay,player,"passport","server","평화");
        assertEquals(List.of(player),carriers); assertEquals(1,payloads.size());
        var message=ProxyCommandMessage.decode(payloads.getFirst(),SECRET,NOW);
        assertEquals(uuid,message.actor()); assertEquals("ssu_lobby",message.serverId());
        assertEquals("passport server 평화",message.command()); assertNotNull(message.requestId());
        assertTrue(messages.isEmpty());
    }

    @Test void registeredServerAliasAndBukkitNamespaceBecomeTheSamePublicCommand() {
        var relay=relay();
        for(String label:List.of("서버","passport:서버")) {
            run(relay,player,label,"건축"); nanos+=400_000_000L;
        }
        run(relay,player,"passport:passport","status");
        assertEquals(List.of("passport server 건축","passport server 건축","passport status"),payloads.stream()
            .map(payload -> ProxyCommandMessage.decode(payload,SECRET,NOW).command()).toList());
    }

    @Test void consoleAndFakeNpcCannotImpersonateAnOnlinePlayer() {
        var relay=relay();
        CommandSender console=(ConsoleCommandSender)Proxy.newProxyInstance(ConsoleCommandSender.class.getClassLoader(),new Class<?>[]{ConsoleCommandSender.class},(object,method,args) -> {
            if(method.getName().equals("sendMessage")) { messages.add((Component)args[args.length-1]); return null; }
            throw new AssertionError("Unexpected console call: "+method.getName());
        });
        run(relay,console,"passport","server","평화");
        Player npc=player(UUID.randomUUID(),true,true); online.put(npc.getUniqueId(),npc);
        run(relay,npc,"passport","server","평화");
        run(relay,player(uuid,false,true),"passport","server","평화");
        assertTrue(payloads.isEmpty()); assertEquals(3,messages.size());
    }

    @Test void DisconnectedAndUnknownPlayersCannotUseAStaleClick() {
        var relay=relay(); Player disconnected=player(UUID.randomUUID(),false,false); online.put(disconnected.getUniqueId(),disconnected);
        run(relay,disconnected,"passport","status"); online.clear(); run(relay,player,"passport","status");
        assertTrue(payloads.isEmpty()); assertEquals(2,messages.size());
    }

    @Test void ReadinessAdmissionAndShutdownAllFailClosed() {
        var relay=relay(); ready=false; run(relay,player,"passport","status");
        ready=true; allowed=false; run(relay,player,"passport","status");
        allowed=true; relay.close(); run(relay,player,"passport","status"); relay.close();
        assertTrue(payloads.isEmpty()); assertEquals(3,messages.size()); assertEquals(1,unregistered);
    }

    @Test void bridgeNeverRelaysManagementCommandsOrArbitraryPlayerSubstitution() {
        var relay=relay();
        for(String[] args:List.of(new String[]{"adminweb"},new String[]{"tp","OtherPlayer"},new String[]{"announce","text"},
            new String[]{"player","OtherPlayer"},new String[]{"server","평화\nstop"})) run(relay,player,"passport",args);
        run(relay,player,"op","OtherPlayer");
        assertTrue(payloads.isEmpty()); assertEquals(6,messages.size());
    }

    @Test void rapidClicksAreBoundedAndQuittingRemovesTheCooldown() {
        var relay=relay(); run(relay,player,"passport","status");
        run(relay,player,"passport","status"); nanos+=399_000_000L; run(relay,player,"passport","status");
        assertEquals(1,payloads.size()); nanos+=1_000_000L; run(relay,player,"passport","status");
        assertEquals(2,payloads.size()); relay.quit(new PlayerQuitEvent(player,Component.empty(),PlayerQuitEvent.QuitReason.DISCONNECTED));
        run(relay,player,"passport","status"); assertEquals(3,payloads.size());
        assertNotEquals(ProxyCommandMessage.decode(payloads.get(0),SECRET,NOW).requestId(),ProxyCommandMessage.decode(payloads.get(2),SECRET,NOW).requestId());
    }

    @Test void missingOutgoingChannelReturnsAnErrorWithoutRetryFloodOrPlayerNameSuggestions() {
        var relay=relay(); failSend=true; run(relay,player,"passport","web"); run(relay,player,"passport","web");
        assertEquals(1,messages.size()); assertTrue(payloads.isEmpty());
        assertEquals(List.of(),relay.onTabComplete(player,null,"passport",new String[]{""}));
    }
}
