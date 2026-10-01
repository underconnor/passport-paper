package io.github.underconnor.passport.paper;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class IdentityDisplayTest {
    @Test void identityFormattingDoesNotChangeBodyColorDecorationsOrActions() {
        Component message=Component.text("본문",NamedTextColor.GOLD).decorate(TextDecoration.BOLD).clickEvent(ClickEvent.openUrl("https://example.test"));
        Component rendered=IdentityDisplay.chat(IdentityDisplay.prefix("회원"),IdentityDisplay.name("Tester","테스트"),message);
        assertNull(rendered.color()); assertEquals(message,rendered.children().getLast());
        assertEquals(NamedTextColor.GRAY,rendered.children().getFirst().color());
        Component plain=IdentityDisplay.chat(IdentityDisplay.prefix("회원"),IdentityDisplay.name("Tester","테스트"),Component.text("기본 본문"));
        assertNull(plain.color()); assertNull(plain.children().getLast().color());
    }
}
