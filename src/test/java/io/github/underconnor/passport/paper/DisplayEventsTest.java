package io.github.underconnor.passport.paper;

import io.github.underconnor.passport.core.Policy;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static io.github.underconnor.passport.paper.IdentityDisplayTest.*;
import static org.junit.jupiter.api.Assertions.*;

class DisplayEventsTest {
    final UUID uuid=UUID.randomUUID();
    boolean staff;
    final Player player=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(object,method,args) -> switch(method.getName()) {
        case "getUniqueId" -> uuid; case "getName" -> "Tester"; case "hasPermission" -> staff && args[0].equals(DisplayEvents.STAFF);
        case "hashCode" -> System.identityHashCode(object); case "equals" -> object==args[0];
        default -> throw new AssertionError("Unexpected player mutation: "+method.getName());
    });
    final AtomicReference<Policy> current=new AtomicReference<>(policy(uuid,"홍길동",false));
    DisplayEvents events(DisplaySettings settings) { return new DisplayEvents(settings,() -> true,id -> Optional.ofNullable(current.get()),"lobby",Clock.systemUTC()); }
    @Test void realJoinQuitEventsUseTheRequestedFormatAndPreserveAnotherPluginsSuppression() {
        var events=events(defaults()); var join=new PlayerJoinEvent(player,Component.text("vanilla"));
        var quit=new PlayerQuitEvent(player,Component.text("vanilla"),PlayerQuitEvent.QuitReason.DISCONNECTED); events.join(join); events.quit(quit);
        assertEquals("[+] Tester (홍길동)",text(join.joinMessage())); assertEquals("[-] Tester (홍길동)",text(quit.quitMessage()));
        join.joinMessage(null); quit.quitMessage(null); events.join(join); events.quit(quit);
        assertNull(join.joinMessage()); assertNull(quit.quitMessage());
    }
    @Test void expiredWrongScopeOrWrongUuidPoliciesDoNotExposeNamesOrAllowChat() {
        var valid=current.get();
        for(Policy invalid:List.of(new Policy(uuid,"active",Set.of("lobby"),"회원","홍길동",2,Instant.now().minusSeconds(61),Instant.now().minusSeconds(1)),
            new Policy(uuid,"active",Set.of("other"),"회원","홍길동",2,valid.issuedAt(),valid.expiresAt()),policy(UUID.randomUUID(),"홍길동",true))) {
            current.set(invalid); var events=events(defaults()); var quit=new PlayerQuitEvent(player,Component.text("old"),PlayerQuitEvent.QuitReason.DISCONNECTED); events.quit(quit);
            assertEquals("[-] Tester",text(quit.quitMessage())); var chat=chat(); events.chat(chat); assertTrue(chat.isCancelled());
        }
    }
    @Test void disabledChannelsKeepExistingMessagesAndRendererAndBlankJoinCanSuppress() {
        var config=new YamlConfiguration(); config.set("display.join.enabled",false); config.set("display.quit.enabled",false); config.set("display.chat.enabled",false);
        var events=events(DisplaySettings.read(config,key -> null)); var original=Component.text("other plugin");
        var join=new PlayerJoinEvent(player,original); var quit=new PlayerQuitEvent(player,original,PlayerQuitEvent.QuitReason.DISCONNECTED); var chat=chat(); var renderer=chat.renderer();
        events.join(join); events.quit(quit); events.chat(chat);
        assertSame(original,join.joinMessage()); assertSame(original,quit.quitMessage()); assertSame(renderer,chat.renderer());
        config.set("display.join.enabled",true); config.set("display.join.format",""); events(DisplaySettings.read(config,key -> null)).join(join); assertNull(join.joinMessage());
    }
    @Test void chatRendererRechecksPolicyBeforeShowingNamesAndDoesNotChangeTheBody() {
        var events=events(defaults()); var chat=chat(); events.chat(chat);
        assertEquals("[회원] Tester (홍길동): hello",text(chat.renderer().render(player,Component.empty(),chat.message(),player)));
        current.set(null);
        assertEquals("Tester: hello",text(chat.renderer().render(player,Component.empty(),chat.message(),player)));
        assertEquals(Component.text("hello"),chat.message());
    }
    @Test void localStaffDisplayPermissionChangesOnlyPresentationAndStillNeedsAValidPolicy() {
        staff=true; var event=chat(); events(defaults()).chat(event);
        assertEquals("[운영진] Tester (홍길동): hello",text(event.renderer().render(player,Component.empty(),event.message(),player)));
        assertFalse(current.get().administrator());
        staff=false;
        assertEquals("[회원] Tester (홍길동): hello",text(event.renderer().render(player,Component.empty(),event.message(),player)));
        current.set(null); staff=true;
        assertEquals("Tester: hello",text(event.renderer().render(player,Component.empty(),event.message(),player)));
    }
    AsyncChatEvent chat() { return new AsyncChatEvent(false,player,new HashSet<>(),ChatRenderer.defaultRenderer(),Component.text("hello"),Component.text("hello"),null); }
}
