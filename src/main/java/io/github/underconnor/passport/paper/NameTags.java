package io.github.underconnor.passport.paper;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;
import java.util.*;
/** Owns only teams created by this instance; leaves other plugins' teams untouched. Main thread only. */
final class NameTags {
    private final Map<Scoreboard,Map<UUID,Team>> owned=new IdentityHashMap<>();
    void update(Player target,IdentityDisplay.Nameplate display) {
        Set<Scoreboard> boards=Collections.newSetFromMap(new IdentityHashMap<>());
        boards.add(Bukkit.getScoreboardManager().getMainScoreboard());
        Bukkit.getOnlinePlayers().forEach(viewer -> boards.add(viewer.getScoreboard()));
        for(Scoreboard board:boards) {
            Map<UUID,Team> teams=owned.computeIfAbsent(board,ignored -> new HashMap<>());
            Team own=teams.get(target.getUniqueId()); Team current=board.getEntryTeam(target.getName());
            if(own!=null && !sameTeam(current,own)) { dispose(own); teams.remove(target.getUniqueId()); own=null; }
            if(current!=null && !sameTeam(current,own)) continue;
            if(display.prefix().equals(Component.empty()) && display.suffix().equals(Component.empty()) && display.color().equals(NamedTextColor.WHITE)) {
                if(own!=null) { dispose(own); teams.remove(target.getUniqueId()); } continue;
            }
            if(own==null) {
                String id="pp"+target.getUniqueId().toString().replace("-","").substring(0,14);
                if(board.getTeam(id)!=null) continue;
                own=board.registerNewTeam(id); own.addEntry(target.getName()); teams.put(target.getUniqueId(),own);
            }
            own.prefix(display.prefix()); own.suffix(display.suffix()); own.color(display.color());
        }
    }
    void remove(UUID uuid) { owned.values().forEach(teams -> { Team team=teams.remove(uuid); if(team!=null) dispose(team); }); }
    void close() { owned.values().forEach(teams -> teams.values().forEach(NameTags::dispose)); owned.clear(); }
    private static boolean sameTeam(Team first,Team second) { return first!=null && second!=null && first.getName().equals(second.getName()); }
    private static void dispose(Team team) { try { team.unregister(); } catch(IllegalStateException ignored) {} }
}
