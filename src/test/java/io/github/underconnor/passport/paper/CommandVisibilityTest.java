package io.github.underconnor.passport.paper;

import net.kyori.adventure.text.Component;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.*;
import org.bukkit.event.server.TabCompleteEvent;
import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CommandVisibilityTest {
    final Set<String> permissions=new HashSet<>(); final List<Component> messages=new ArrayList<>();
    final Map<String,Command> commands=new HashMap<>();
    final Player player=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(object,method,args) -> switch(method.getName()) {
        case "hasPermission" -> permissions.contains(args[0]);
        case "getEffectivePermissions" -> permissions.stream().map(permission -> new PermissionAttachmentInfo((Player)object,permission,null,true)).collect(java.util.stream.Collectors.toSet());
        case "sendMessage" -> { if(args[args.length-1] instanceof Component component) messages.add(component); yield null; }
        case "hashCode" -> System.identityHashCode(object); case "equals" -> object==args[0];
        default -> throw new AssertionError("Unexpected permission change: "+method.getName());
    });
    CommandVisibility filter(List<CommandVisibility.Entry> entries) { return new CommandVisibility(commands::get,p -> true,entries); }
    Command command(String name,String permission) {
        Command command=new Command(name) { @Override public boolean execute(CommandSender sender,String label,String[] args) { return true; } };
        command.setPermission(permission); commands.put(name,command); return command;
    }
    Plugin plugin(String name) {
        return (Plugin)Proxy.newProxyInstance(Plugin.class.getClassLoader(),new Class<?>[]{Plugin.class},(object,method,args) -> switch(method.getName()) {
            case "getName" -> name;
            case "hashCode" -> System.identityHashCode(object); case "equals" -> object==args[0];
            default -> throw new AssertionError("Unexpected plugin call: "+method.getName());
        });
    }
    Command owned(String name,String permission,String owner,String... aliases) {
        class Owned extends Command implements PluginIdentifiableCommand {
            Owned() { super(name); }
            @Override public boolean execute(CommandSender sender,String label,String[] args) { throw new AssertionError("Must not execute"); }
            @Override public Plugin getPlugin() { return plugin(owner); }
        }
        Command command=new Owned(); command.setPermission(permission); commands.put(name,command);
        for(String alias:aliases) commands.put(alias,command);
        return command;
    }
    PluginCommand luckPerms(TabCompleter completer,String... aliases) throws Exception {
        var constructor=PluginCommand.class.getDeclaredConstructor(String.class,Plugin.class); constructor.setAccessible(true);
        PluginCommand command=constructor.newInstance("luckperms",plugin("LuckPerms")); command.setTabCompleter(completer);
        commands.put("luckperms",command); for(String alias:aliases) commands.put(alias,command);
        return command;
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
    @Test void nullPermissionManagementFamiliesAndNamespacesStayHiddenWithoutChangingPublicCommands() throws Exception {
        luckPerms((sender,command,label,args) -> List.of(),"lp","perm","perms","permission","permissions","luckperms:lp");
        owned("papi",null,"PlaceholderAPI","placeholderapi","placeholderapi:papi");
        owned("mv",null,"Multiverse-Core","mvtp","mvlist","multiverse-core:mv");
        owned("fawe",null,"FastAsyncWorldEdit","we","fastasyncworldedit:fawe");
        owned("lobby",null,"OverworldLobby","overworldlobby","overworldlobby:lobby");
        owned("spawn","overworld.lobby.spawn","OverworldLobby","overworldlobby:spawn");
        owned("setspawn",null,"OverworldLobby");
        command("tpa",null); command("home",null); command("back",null);
        permissions.addAll(Set.of("overworld.lobby.spawn","overworld.lobby.fly","fawe.plotsquared","fawe.plotsquared.trusted","placeholderapi.updatenotify","multiverse.access.world"));
        var labels=new HashSet<>(commands.keySet()); filter(List.of()).send(new PlayerCommandSendEvent(player,labels));
        assertEquals(Set.of("lobby","spawn","tpa","home","back"),labels);
    }
    @Test void luckPermsOwnCompleterPreservesCustomAuthorizedCommandsAndReflectsRevocation() throws Exception {
        var calls=new ArrayList<String>();
        luckPerms((sender,command,label,args) -> {
            assertArrayEquals(new String[]{""},args); calls.add(label);
            return sender.hasPermission("luckperms.custom.read") ? List.of("custom") : List.of();
        },"lp","perm","luckperms:permissions");
        var filter=filter(List.of());
        for(boolean granted:List.of(false,true,false)) {
            if(granted) permissions.add("luckperms.custom.read"); else permissions.clear();
            var labels=new HashSet<>(commands.keySet()); filter.send(new PlayerCommandSendEvent(player,labels));
            assertEquals(granted ? commands.keySet() : Set.of(),labels);
        }
        assertEquals(12,calls.size());
    }
    @Test void luckPermsMissingNullOrFailedCompleterNeverFallsBackToPlayerNames() throws Exception {
        for(TabCompleter completer:Arrays.<TabCompleter>asList(null,(sender,command,label,args) -> null,(sender,command,label,args) -> { throw new IllegalStateException("unavailable"); })) {
            luckPerms(completer,"lp"); var labels=new HashSet<>(commands.keySet()); filter(List.of()).send(new PlayerCommandSendEvent(player,labels));
            assertTrue(labels.isEmpty());
        }
    }
    @Test void worldEditDoubleSlashLabelsRetainExactCommandPermissionAndOwner() {
        owned("/wand","worldedit.wand","FastAsyncWorldEdit","worldedit:/wand");
        owned("/set","worldedit.region.set","FastAsyncWorldEdit","fawe:/set");
        owned("brush",null,"FastAsyncWorldEdit");
        permissions.add("worldedit.wand");
        var labels=new HashSet<>(commands.keySet()); filter(List.of()).send(new PlayerCommandSendEvent(player,labels));
        assertEquals(Set.of("/wand","worldedit:/wand","brush"),labels);
        permissions.clear(); labels=new HashSet<>(commands.keySet()); filter(List.of()).send(new PlayerCommandSendEvent(player,labels));
        assertTrue(labels.isEmpty());
        assertEquals("/wand",CommandVisibility.root("//wand "));
    }
    @Test void declaredDynamicPermissionAndLimitedFamilyPermissionsRemainAuthoritative() {
        owned("mvtp","multiverse.teleport.self.w.world","Multiverse-Core");
        owned("mv",null,"Multiverse-Core"); owned("papi",null,"PlaceholderAPI");
        permissions.addAll(Set.of("multiverse.teleport.self.w.world","placeholderapi.parse"));
        var labels=new HashSet<>(commands.keySet()); filter(List.of()).send(new PlayerCommandSendEvent(player,labels));
        assertEquals(commands.keySet(),labels);
        permissions.remove("multiverse.teleport.self.w.world"); labels=new HashSet<>(commands.keySet()); filter(List.of()).send(new PlayerCommandSendEvent(player,labels));
        assertEquals(Set.of("papi"),labels);
    }
    @Test void lobbyAdminAliasRequiresLocalAdminPermissionAndDoesNotUseDisplayOrInspectPermission() {
        owned("lobby",null,"OverworldLobby","overworldlobby","overworldlobby:lobby");
        permissions.addAll(Set.of("passport.display.staff",CommandVisibility.INSPECT));
        var labels=new HashSet<>(commands.keySet()); filter(List.of()).send(new PlayerCommandSendEvent(player,labels)); assertEquals(Set.of("lobby"),labels);
        permissions.add("overworld.lobby.admin"); labels=new HashSet<>(commands.keySet()); filter(List.of()).send(new PlayerCommandSendEvent(player,labels));
        assertEquals(commands.keySet(),labels);
    }
    @Test void explicitPermissionNodesRegisteredByPluginsSupportInheritedServerContextGrants() {
        owned("fawe",null,"FastAsyncWorldEdit");
        var filter=new CommandVisibility(commands::get,p -> true,List.of(),() -> Set.of("worldedit.selection.hpos"));
        permissions.add("worldedit.selection.hpos");
        var labels=new HashSet<>(commands.keySet()); filter.send(new PlayerCommandSendEvent(player,labels)); assertEquals(Set.of("fawe"),labels);
    }
    @Test void typedManagementCompletionIsClearedOnBothEventPathsButPublicCommandsKeepSuggestions() {
        owned("/wand","worldedit.wand","FastAsyncWorldEdit"); owned("papi",null,"PlaceholderAPI");
        var filter=filter(List.of());
        for(String input:List.of("/lp ","/luckperms:permissions user ","/papi parse ","/mv ","/overworldlobby ","//wand ")) {
            var sync=new TabCompleteEvent(player,input,new ArrayList<>(List.of("Alice","reload")),true,null); filter.complete(sync);
            assertTrue(sync.isCancelled(),input); assertTrue(sync.getCompletions().isEmpty());
            var async=new AsyncTabCompleteEvent(player,input,true,null); async.setCompletions(List.of("Alice","reload")); filter.completeAsync(async);
            assertTrue(async.isCancelled(),input); assertTrue(async.isHandled()); assertTrue(async.getCompletions().isEmpty());
        }
        for(String input:List.of("/tpa ","/home ","/back ","/lobby fly ","/서버 ")) {
            var sync=new TabCompleteEvent(player,input,new ArrayList<>(List.of("Alice")),true,null); filter.complete(sync);
            assertFalse(sync.isCancelled(),input); assertEquals(List.of("Alice"),sync.getCompletions());
        }
    }
    @Test void rootCompletionRemovesManagementSuggestionsAndPreservesDoubleSlashAndTooltip() {
        owned("/wand","worldedit.wand","FastAsyncWorldEdit"); owned("/set","worldedit.region.set","FastAsyncWorldEdit");
        permissions.add("worldedit.wand"); var filter=filter(List.of());
        var sync=new TabCompleteEvent(player,"/",new ArrayList<>(List.of("/lp","/papi","/wand","//set","/home")),true,null); filter.complete(sync);
        assertEquals(List.of("/wand","/home"),sync.getCompletions());
        var async=new AsyncTabCompleteEvent(player,"/",true,null);
        var tooltip=Component.text("allowed wand");
        async.completions(List.of(AsyncTabCompleteEvent.Completion.completion("//wand",tooltip),AsyncTabCompleteEvent.Completion.completion("/lp"),AsyncTabCompleteEvent.Completion.completion("/home")));
        filter.completeAsync(async);
        assertEquals(List.of("//wand","/home"),async.getCompletions()); assertEquals(tooltip,async.completions().getFirst().tooltip());
    }
    @Test void asynchronousCompletionRunsOneBatchOnServerThreadAndClosesWhenItCannotRun() {
        int[] batches={0};
        var filter=new CommandVisibility(commands::get,p -> true,List.of(),Set::of,work -> { batches[0]++; return Optional.empty(); });
        var event=new AsyncTabCompleteEvent(player,"/lp ",true,null); event.setCompletions(List.of("user")); filter.completeAsync(event);
        assertEquals(1,batches[0]); assertTrue(event.isCancelled()); assertTrue(event.isHandled()); assertTrue(event.getCompletions().isEmpty());
    }
    @Test void completionNeverAffectsOrdinaryChatOrChangesExecutionPermissions() {
        var filter=filter(List.of()); var event=new AsyncTabCompleteEvent(player,"hello ",false,null); event.setCompletions(List.of("Alice")); filter.completeAsync(event);
        assertFalse(event.isCancelled()); assertEquals(List.of("Alice"),event.getCompletions());
        for(String input:List.of("/lp editor","/papi parse me x","/mv list","//set stone","/overworldlobby reload")) {
            var execute=new PlayerCommandPreprocessEvent(player,input,new HashSet<>()); filter.execute(execute); assertFalse(execute.isCancelled());
        }
    }
}
