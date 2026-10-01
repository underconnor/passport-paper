package io.github.underconnor.passport.paper;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

final class IdentityDisplay {
    private IdentityDisplay() {}
    static Component prefix(String role) { return role.isBlank() ? Component.empty() : Component.text("["+role+"] ",NamedTextColor.GRAY); }
    static Component name(String ign,String real) {
        Component name=Component.text(ign,NamedTextColor.WHITE);
        return real.isBlank() || real.equals(ign) ? name : name.append(Component.text(" ("+real+")",NamedTextColor.GRAY));
    }
    static Component chat(Component prefix,Component name,Component message) {
        // A colorless root prevents the nickname/prefix color leaking into the original body.
        return Component.empty().append(prefix).append(name).append(Component.text(": ",NamedTextColor.GRAY)).append(message);
    }
}
