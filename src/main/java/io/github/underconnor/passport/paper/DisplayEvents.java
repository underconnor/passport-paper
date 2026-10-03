package io.github.underconnor.passport.paper;

import io.github.underconnor.passport.core.Policy;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import java.time.Clock;
import java.util.*;
import java.util.function.*;

final class DisplayEvents implements Listener {
    static final String STAFF="passport.display.staff";
    private final DisplaySettings settings;
    private final IdentityDisplay display;
    private final BooleanSupplier ready;
    private final Function<UUID,Optional<Policy>> policies;
    private final String server;
    private final Clock clock;
    DisplayEvents(DisplaySettings settings,BooleanSupplier ready,Function<UUID,Optional<Policy>> policies,String server,Clock clock) {
        this.settings=settings; this.display=new IdentityDisplay(settings); this.ready=ready; this.policies=policies; this.server=server; this.clock=clock;
    }
    Policy policy(UUID uuid) { return ready.getAsBoolean() ? policies.apply(uuid).filter(p -> p.minecraftUuid().equals(uuid) && p.allows(server,clock.instant())).orElse(null) : null; }
    @EventHandler(priority=EventPriority.HIGHEST) public void join(PlayerJoinEvent event) {
        if(settings.join().enabled() && event.joinMessage()!=null)
            event.joinMessage(settings.join().format().isEmpty() ? null : display.render(settings.join(),event.getPlayer().getName(),policy(event.getPlayer().getUniqueId()),Component.empty(),"+",event.getPlayer().hasPermission(STAFF)));
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void quit(PlayerQuitEvent event) {
        if(settings.quit().enabled() && event.quitMessage()!=null)
            event.quitMessage(settings.quit().format().isEmpty() ? null : display.render(settings.quit(),event.getPlayer().getName(),policy(event.getPlayer().getUniqueId()),Component.empty(),"-",event.getPlayer().hasPermission(STAFF)));
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void chat(AsyncChatEvent event) {
        if(policy(event.getPlayer().getUniqueId())==null) { event.setCancelled(true); return; }
        if(settings.chat().enabled()) event.renderer((source,name,message,viewer) -> display.render(settings.chat(),source.getName(),policy(source.getUniqueId()),message,"",source.hasPermission(STAFF)));
    }
}
