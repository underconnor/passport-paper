package io.github.underconnor.passport.paper;

import io.github.underconnor.passport.core.Policy;
import net.kyori.adventure.text.*;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.*;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IdentityDisplayTest {
    static DisplaySettings defaults() { return DisplaySettings.read(new YamlConfiguration(),key -> null); }
    static Policy policy(UUID uuid,String real,boolean administrator) {
        Instant now=Instant.now(); return new Policy(uuid,"active",Set.of("lobby"),"회원",real,1,now,now.plusSeconds(60),true,"26",administrator,Map.of());
    }
    static String text(Component value) { return PlainTextComponentSerializer.plainText().serialize(value); }
    static Component part(Component value,String text) {
        if(value instanceof TextComponent literal && literal.content().equals(text)) return value;
        for(Component child:value.children()) { Component found=part(child,text); if(found!=null) return found; }
        return null;
    }
    @Test void joinAndQuitHaveWhiteBracketsColoredMarkersAndConsentName() {
        var settings=defaults(); var display=new IdentityDisplay(settings); var policy=policy(UUID.randomUUID(),"홍길동",false);
        Component join=display.render(settings.join(),"Tester",policy,Component.empty(),"+");
        Component quit=display.render(settings.quit(),"Tester",policy,Component.empty(),"-");
        assertEquals("[+] Tester (홍길동)",text(join)); assertEquals("[-] Tester (홍길동)",text(quit));
        assertEquals(NamedTextColor.WHITE,part(join,"[").color()); assertEquals(NamedTextColor.WHITE,part(join,"] ").color());
        assertEquals(NamedTextColor.GREEN,part(join,"+").color()); assertEquals(NamedTextColor.RED,part(quit,"-").color());
        assertEquals(NamedTextColor.WHITE,part(join,"Tester").color()); assertEquals(NamedTextColor.GRAY,part(join," (홍길동)").color());
    }
    @Test void transferHasWhiteBracketsAndIgnWithOnlyTheMarkerOrangeAndTheNameGray() {
        var settings=defaults();
        Component transfer=new IdentityDisplay(settings).render(settings.transfer(),"Tester",policy(UUID.randomUUID(),"홍길동",false),Component.empty(),">");
        assertEquals("[>] Tester (홍길동)",text(transfer));
        assertEquals(NamedTextColor.WHITE,part(transfer,"[").color());
        assertEquals(NamedTextColor.WHITE,part(transfer,"] ").color());
        assertEquals(TextColor.fromHexString("#FFAA00"),part(transfer,">").color());
        assertEquals(NamedTextColor.WHITE,part(transfer,"Tester").color());
        assertEquals(NamedTextColor.GRAY,part(transfer," (홍길동)").color());
        var config=new YamlConfiguration(); config.set("display.transfer.format","{ign} {marker}"); config.set("display.transfer.color","aqua");
        var custom=DisplaySettings.read(config,key -> null);
        assertEquals("{ign} {marker}",custom.transfer().format()); assertEquals(NamedTextColor.AQUA,custom.transfer().color());
    }
    @Test void onlyCentralAdministratorSelectsTheStaffLabelAndDistinctColor() {
        var settings=defaults(); var display=new IdentityDisplay(settings); var member=policy(UUID.randomUUID(),"홍길동",false);
        var staff=policy(member.minecraftUuid(),"홍길동",true);
        Component regular=display.render(settings.chat(),"Tester",member,Component.text("hello"),"");
        Component admin=display.render(settings.chat(),"Tester",staff,Component.text("hello"),"");
        assertEquals("[회원] Tester (홍길동): hello",text(regular)); assertEquals("[운영진] Tester (홍길동): hello",text(admin));
        assertEquals(TextColor.fromHexString("#22C55E"),part(regular,"[회원] ").color());
        assertEquals(TextColor.fromHexString("#A3E635"),part(admin,"[운영진] ").color());
        Policy misleading=new Policy(member.minecraftUuid(),"active",Set.of("lobby"),"운영진","",1,member.issuedAt(),member.expiresAt());
        assertEquals("[비회원] Tester: hello",text(display.render(settings.chat(),"Tester",misleading,Component.text("hello"),"")));
    }
    @Test void nonmemberPrefixIsGrayAcrossRoleSurfacesWithoutOverridingStaffOrChatBody() {
        var settings=defaults(); var display=new IdentityDisplay(settings); Instant now=Instant.now();
        var nonmember=new Policy(UUID.randomUUID(),"active",Set.of("lobby"),"","개발용 계정",1,now,now.plusSeconds(60),false,"",false,Map.of());
        Component message=Component.text("hello",NamedTextColor.GOLD);
        Component chat=display.render(settings.chat(),"Tester",nonmember,message,"");
        assertEquals("[비회원] Tester (개발용 계정): hello",text(chat));
        assertEquals(NamedTextColor.GRAY,part(chat,"[비회원] ").color());
        assertNull(chat.color()); assertEquals(message,chat.children().getLast());
        Component tab=display.render(settings.tab(),"Tester",nonmember,Component.empty(),"");
        assertEquals("[비회원] Tester (개발용 계정)",text(tab));
        assertEquals(NamedTextColor.GRAY,part(tab,"[비회원] ").color());
        assertEquals("[운영진] Tester (개발용 계정)",text(display.render(settings.tab(),"Tester",nonmember,Component.empty(),"",true)));
        assertEquals("Tester",text(display.render(settings.tab(),"Tester",null,Component.empty(),"")));
        var config=new YamlConfiguration(); config.set("colors.non-member","dark_gray"); config.set("display.nameplate.format","{role}{ign}{real_name}");
        var custom=new IdentityDisplay(DisplaySettings.read(config,key -> null)).nameplate("Tester",nonmember);
        assertEquals("[비회원] ",text(custom.prefix())); assertEquals(NamedTextColor.DARK_GRAY,part(custom.prefix(),"[비회원] ").color());
    }
    @Test void originalChatBodyKeepsItsColorDecorationAndActionsWithoutIdentityInheritance() {
        var settings=defaults(); var display=new IdentityDisplay(settings);
        Component message=Component.text("본문",NamedTextColor.GOLD).decorate(TextDecoration.BOLD).clickEvent(ClickEvent.openUrl("https://example.test"));
        Component rendered=display.render(settings.chat(),"Tester",policy(UUID.randomUUID(),"테스트",false),message,"");
        assertNull(rendered.color()); assertEquals(message,rendered.children().getLast());
        Component plain=display.render(settings.chat(),"Tester",null,Component.text("기본 본문"),"");
        assertNull(plain.color()); assertNull(plain.children().getLast().color());
    }
    @Test void missingConsentDoesNotLeaveEmptyParenthesesAndNamesCannotInjectTemplates() {
        var settings=defaults(); var display=new IdentityDisplay(settings);
        assertEquals("[+] Tester",text(display.render(settings.join(),"Tester",policy(UUID.randomUUID(),"",false),Component.empty(),"+")));
        assertEquals("[+] Tester (<red>{message})",text(display.render(settings.join(),"Tester",policy(UUID.randomUUID(),"<red>{message}",false),Component.empty(),"+")));
    }
    @Test void configAndExistingEnvironmentOptOutsCanDisableEachSurface() {
        var config=new YamlConfiguration(); config.set("display.join.enabled",false); config.set("display.tab.enabled",false);
        config.set("display.quit.format","{ign} left"); config.set("display.quit.color","gold"); config.set("colors.member","#123456");
        var settings=DisplaySettings.read(config,key -> key.equals("PASSPORT_CHAT_PREFIX") || key.equals("PASSPORT_NAME_TAG") ? "false" : "true");
        assertFalse(settings.join().enabled()); assertFalse(settings.chat().enabled()); assertFalse(settings.tab().enabled()); assertFalse(settings.nameplate().enabled());
        assertEquals(NamedTextColor.GOLD,settings.quit().color()); assertEquals(TextColor.fromHexString("#123456"),settings.member());
        assertEquals("Tester left",text(new IdentityDisplay(settings).render(settings.quit(),"Tester",null,Component.empty(),"-")));
    }
    @Test void bundledConfigMatchesDefaultsAndRejectsInvalidPlaceholdersColorsAndNameplates() throws Exception {
        try(var reader=new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/config.yml")),StandardCharsets.UTF_8)) {
            assertEquals(defaults(),DisplaySettings.read(YamlConfiguration.loadConfiguration(reader),key -> null));
        }
        for(String path:List.of("display.chat.format","colors.member","colors.non-member","display.nameplate.format","display.join.enabled")) {
            var config=new YamlConfiguration(); config.set(path,"invalid {secret}");
            assertThrows(IllegalArgumentException.class,() -> DisplaySettings.read(config,key -> null),path);
        }
    }
    @Test void nameplateSeparatesConfiguredTeamPrefixAndSuffixWithoutChangingIgn() {
        var config=new YamlConfiguration(); config.set("display.nameplate.format","{role}{ign}{real_name}");
        var settings=DisplaySettings.read(config,key -> null);
        var tag=new IdentityDisplay(settings).nameplate("Tester",policy(UUID.randomUUID(),"홍길동",true));
        assertEquals("[운영진] ",text(tag.prefix())); assertEquals(" (홍길동)",text(tag.suffix())); assertEquals(NamedTextColor.WHITE,tag.color());
    }
}
