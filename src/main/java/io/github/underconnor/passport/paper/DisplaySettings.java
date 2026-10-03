package io.github.underconnor.passport.paper;

import net.kyori.adventure.text.format.*;
import org.bukkit.configuration.ConfigurationSection;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Immutable settings are safe to read from Paper's asynchronous chat renderer. */
record DisplaySettings(Style join,Style quit,Style chat,Style tab,Style nameplate,
                       TextColor ign,TextColor realName,TextColor member,TextColor administrator) {
    record Style(boolean enabled,String format,TextColor color,TextColor marker) {}
    private static final Pattern TOKEN=Pattern.compile("\\{([a-z_]+)}");
    static DisplaySettings read(ConfigurationSection config,Function<String,String> environment) {
        return new DisplaySettings(
            style(config,"join","[{marker}] {ign}{real_name}","#FFFFFF","#55FF55",null,environment),
            style(config,"quit","[{marker}] {ign}{real_name}","#FFFFFF","#FF5555",null,environment),
            style(config,"chat","{role}{ign}{real_name}: {message}","#AAAAAA",null,"PASSPORT_CHAT_PREFIX",environment),
            style(config,"tab","{role}{ign}{real_name}","#FFFFFF",null,"PASSPORT_TAB_PREFIX",environment),
            style(config,"nameplate","{ign}{real_name}","#FFFFFF",null,"PASSPORT_NAME_TAG",environment),
            color(config,"colors.ign","#FFFFFF"),color(config,"colors.real-name","#AAAAAA"),
            color(config,"colors.member","#22C55E"),color(config,"colors.administrator","#A3E635"));
    }
    private static Style style(ConfigurationSection config,String name,String fallback,String color,String marker,
                               String legacy,Function<String,String> environment) {
        String path="display."+name;
        Object raw=config.get(path+".enabled",true);
        if(!(raw instanceof Boolean enabled)) throw new IllegalArgumentException(path+".enabled must be boolean");
        // Preserve an existing deployment's opt-out; config.yml can also disable each surface.
        if(legacy!=null && "false".equalsIgnoreCase(environment.apply(legacy))) enabled=false;
        String format=config.getString(path+".format",fallback);
        if(format==null || format.length()>512 || format.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(path+".format is invalid");
        var matcher=TOKEN.matcher(format);
        Set<String> allowed=new HashSet<>(Set.of("role","ign","real_name"));
        if(name.equals("chat")) allowed.add("message");
        if(name.equals("join") || name.equals("quit")) allowed.add("marker");
        while(matcher.find()) if(!allowed.contains(matcher.group(1))) throw new IllegalArgumentException(path+".format has an unknown placeholder");
        if(name.equals("nameplate") && !format.isEmpty() && (format.indexOf("{ign}")<0 || format.indexOf("{ign}")!=format.lastIndexOf("{ign}")))
            throw new IllegalArgumentException(path+".format requires exactly one {ign}");
        return new Style(enabled,format,color(config,path+".color",color),marker==null ? null : color(config,path+".marker-color",marker));
    }
    private static TextColor color(ConfigurationSection config,String path,String fallback) {
        String value=config.getString(path,fallback);
        TextColor result=value==null ? null : value.startsWith("#") ? TextColor.fromHexString(value) : NamedTextColor.NAMES.value(value.toLowerCase(Locale.ROOT));
        if(result==null) throw new IllegalArgumentException(path+" must be a hex or Minecraft color");
        return result;
    }
}
