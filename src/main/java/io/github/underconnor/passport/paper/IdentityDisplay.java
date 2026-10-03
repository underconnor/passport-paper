package io.github.underconnor.passport.paper;
import io.github.underconnor.passport.core.Policy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import java.util.*;
import java.util.regex.Pattern;

final class IdentityDisplay {
    record Nameplate(Component prefix,Component suffix,NamedTextColor color) {}
    private static final Pattern TOKEN=Pattern.compile("\\{([a-z_]+)}");
    private final DisplaySettings settings;
    IdentityDisplay(DisplaySettings settings) { this.settings=settings; }
    Component render(DisplaySettings.Style style,String ign,Policy policy,Component message,String marker) {
        return render(style,ign,policy,message,marker,false);
    }
    Component render(DisplaySettings.Style style,String ign,Policy policy,Component message,String marker,boolean staff) {
        return render(style.format(),style,values(style,ign,policy,message,marker,staff));
    }
    Nameplate nameplate(String ign,Policy policy) {
        return nameplate(ign,policy,false);
    }
    Nameplate nameplate(String ign,Policy policy,boolean staff) {
        var style=settings.nameplate(); String format=style.format();
        if(format.isEmpty()) return new Nameplate(Component.empty(),Component.empty(),NamedTextColor.WHITE);
        int split=format.indexOf("{ign}"); var values=values(style,ign,policy,Component.empty(),"",staff);
        return new Nameplate(render(format.substring(0,split),style,values),render(format.substring(split+5),style,values),
            NamedTextColor.nearestTo(style.color()));
    }
    private Map<String,Component> values(DisplaySettings.Style style,String ign,Policy policy,Component message,String marker,boolean staff) {
        String real=policy==null ? "" : policy.displayName();
        Component role=Component.empty();
        if(policy!=null) {
            if(policy.administrator() || staff) role=Component.text("[운영진] ",settings.administrator());
            else if(policy.member()) role=Component.text("[회원] ",settings.member());
            else role=Component.text("[비회원] ",settings.nonMember());
        }
        return Map.of("role",role,"ign",Component.text(ign,settings.ign()),
            "real_name",real.isBlank() || real.equals(ign) ? Component.empty() : Component.text(" ("+real+")",settings.realName()),
            "message",message,"marker",Component.text(marker,style.marker()==null ? style.color() : style.marker()));
    }
    private Component render(String format,DisplaySettings.Style style,Map<String,Component> values) {
        // Only the trusted template is tokenized. Names and message components are never interpreted.
        // A colorless root prevents role/name colors from leaking into the original chat body.
        Component output=Component.empty(); var matcher=TOKEN.matcher(format); int offset=0;
        while(matcher.find()) {
            if(matcher.start()>offset) output=output.append(Component.text(format.substring(offset,matcher.start()),style.color()));
            output=output.append(values.getOrDefault(matcher.group(1),Component.empty())); offset=matcher.end();
        }
        if(offset<format.length()) output=output.append(Component.text(format.substring(offset),style.color()));
        return output;
    }
}
