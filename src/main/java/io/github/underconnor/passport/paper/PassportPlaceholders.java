package io.github.underconnor.passport.paper;
import io.github.underconnor.passport.api.*;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import java.util.Locale;
final class PassportPlaceholders extends PlaceholderExpansion {
    private final PassportIdentityService identities;
    PassportPlaceholders(PassportIdentityService identities) { this.identities=identities; }
    @Override public String getIdentifier() { return "passport"; }
    @Override public String getAuthor() { return "underconnor"; }
    @Override public String getVersion() { return "0.2.0"; }
    @Override public boolean persist() { return true; }
    @Override public String onRequest(OfflinePlayer player,String params) {
        String key=params.toLowerCase(Locale.ROOT);
        if (!java.util.Set.of("real_name","member","admission_year").contains(key)) return null;
        PassportIdentity identity=player==null ? null : identities.identity(player.getUniqueId()).orElse(null);
        if(identity==null) return key.equals("member") ? "false" : "";
        return switch(key) { case "real_name" -> identity.realName(); case "member" -> Boolean.toString(identity.member());
            default -> identity.admissionYear()==null ? "" : identity.admissionYear(); };
    }
}
