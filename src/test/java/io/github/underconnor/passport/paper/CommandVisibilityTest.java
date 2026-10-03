package io.github.underconnor.passport.paper;

import net.kyori.adventure.text.Component;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CommandVisibilityTest {
    final Set<String> permissions=new HashSet<>(); final List<Component> messages=new ArrayList<>();
    final Map<String,Command> commands=new HashMap<>();
    final Player player=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(object,method,args) -> switch(method.getName()) {
        case "hasPermission" -> permissions.contains(args[0]);
        case "sendMessage" -> { if(args[args.length-1] instanceof Component component) messages.add(component); yield null; }
        case "hashCode" -> System.identityHashCode(object); case "equals" -> object==args[0];
        default -> throw new AssertionError("Unexpected permission change: "+method.getName());
    });
    CommandVisibility filter(List<CommandVisibility.Entry> entries) { return new CommandVisibility(commands::get,p -> true,entries); }
    Command command(String name,String permission) {
        Command command=new Command(name) { @Override public boolean execute(CommandSender sender,String label,String[] args) { return true; } };
        command.setPermission(permission); commands.put(name,command); return command;
    }
    @Test void commandTreeHidesInformationAndDeniedCommandsWithoutGuessingNativePermissions() {
        command("tpa","travel.tpa"); command("home",null); command("adminhome",null); command("secret","admin.secret"); permissions.add("travel.tpa");
        Set<String> labels=new HashSet<>(Set.of("plugins","pl","version","ver","about","icanhasbukkit","bukkit:plugins","minecraft:help","bukkit:?","help","?","도움말","tpa","home","adminhome","secret","native","서버"));
        filter(List.of()).send(new PlayerCommandSendEvent(player,labels));
        assertEquals(Set.of("help","?","도움말","tpa","home","adminhome","native","서버"),labels);
    }
    @Test void informationAliasesAreAlsoBlockedWhenTypedDirectlyAndInspectNeedsExplicitPermission() {
        var filter=filter(List.of());
        for(String text:List.of("/plugins","/bukkit:pl","/VERSION","/paper:about","/icanhasbukkit")) {
            var event=new PlayerCommandPreprocessEvent(player,text,new HashSet<>()); filter.execute(event); assertTrue(event.isCancelled(),text);
        }
        permissions.add(CommandVisibility.INSPECT);
        var event=new PlayerCommandPreprocessEvent(player,"/bukkit:plugins",new HashSet<>()); filter.execute(event); assertFalse(event.isCancelled());
        Set<String> labels=new HashSet<>(Set.of("plugins","bukkit:help")); filter.send(new PlayerCommandSendEvent(player,labels)); assertEquals(2,labels.size());
        assertTrue(messages.stream().noneMatch(message -> IdentityDisplayTest.text(message).contains("installed")));
    }
    @Test void helpOnlyOffersInstalledPermittedCommandsAndTwoExplicitProxyRoutes() {
        command("tpa","travel.tpa"); command("home",null); command("plugins",null); permissions.add("travel.tpa");
        var tpa=new CommandVisibility.Entry("/tpa","이동","",false); var home=new CommandVisibility.Entry("/home","집","extra.home",false);
        var proxy=new CommandVisibility.Entry("/서버","서버","",true);
        var filter=filter(List.of(tpa,home,proxy,new CommandVisibility.Entry("/fly","비행","",false),new CommandVisibility.Entry("/plugins","정보","",false)));
        assertEquals(List.of(tpa,proxy),filter.available(player));
        for(String command:List.of("/help","/?","/도움말","/bukkit:help")) {
            messages.clear(); var event=new PlayerCommandPreprocessEvent(player,command,new HashSet<>()); filter.execute(event); assertTrue(event.isCancelled());
            assertEquals(3,messages.size()); assertEquals(net.kyori.adventure.text.event.ClickEvent.suggestCommand("/tpa "),messages.get(1).clickEvent());
        }
        var invalid=new CommandVisibility(commands::get,p -> false,List.of(proxy)); assertTrue(invalid.available(player).isEmpty());
    }
    @Test void regularTravelCommandsKeepTheirNormalExecution() {
        for(String command:List.of("/tpa Alice","/home","/back","/lobby","/서버 건축","/trust Alice")) {
            var event=new PlayerCommandPreprocessEvent(player,command,new HashSet<>()); filter(List.of()).execute(event); assertFalse(event.isCancelled(),command);
        }
    }
    @Test void helpConfigRejectsArbitraryProxyCommands() {
        var config=new YamlConfiguration(); config.set("help.entries",List.of(Map.of("command","/op Someone","proxy",true)));
        assertThrows(IllegalArgumentException.class,() -> CommandVisibility.read(config));
        config.set("help.entries",List.of(Map.of("command","/passport help","proxy",true),Map.of("command","/home","permission","travel.home")));
        assertEquals(2,CommandVisibility.read(config).size());
    }
    @Test void editorRequiresBothItsSpecificPermissionAndTheRegisteredCommandPermission() {
        command("lp","luckperms.use");
        var entry=new CommandVisibility.Entry("/lp editor","권한 편집기","luckperms.editor",false); var filter=filter(List.of(entry));
        assertTrue(filter.available(player).isEmpty()); permissions.add("luckperms.editor");
        assertTrue(filter.available(player).isEmpty()); permissions.add("luckperms.use");
        assertEquals(List.of(entry),filter.available(player)); permissions.remove("luckperms.editor");
        assertTrue(filter.available(player).isEmpty());
    }
}
