package io.github.underconnor.passport.paper;

import com.destroystokyo.paper.profile.PlayerProfile;
import io.github.underconnor.passport.core.Policy;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerConnection;
import io.papermc.paper.connection.PlayerLoginConnection;
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent;
import io.papermc.paper.event.player.PlayerServerFullCheckEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Uses the connecting profile, never an online player with the same name or UUID. */
final class LoginAdmission implements Listener {
    private final BooleanSupplier ready;
    private final Function<UUID, Optional<Policy>> policies;
    private final String serverId;
    private final Component denied;
    private final Clock clock;

    LoginAdmission(BooleanSupplier ready, Function<UUID, Optional<Policy>> policies, String serverId,
                   Component denied, Clock clock) {
        this.ready = ready;
        this.policies = policies;
        this.serverId = serverId;
        this.denied = denied;
        this.clock = clock;
    }

    // Paper calls this only for its capacity decision, after ban/whitelist/IP-ban checks.
    // Use the earliest priority so later listeners may still deny for their own reasons.
    @EventHandler(priority = EventPriority.LOWEST)
    public void fullServer(PlayerServerFullCheckEvent event) {
        if (!event.isAllowed() && allowedPolicy(event.getPlayerProfile()).map(Policy::administrator).orElse(false)) {
            event.allow(true);
        }
    }

    // Paper validates both before configuration and again immediately before joining.
    // Never call allow(): it would clear bans, whitelist failures and other plugin kicks.
    @EventHandler(priority = EventPriority.HIGHEST)
    public void validateLogin(PlayerConnectionValidateLoginEvent event) {
        if (event.isAllowed() && allowedPolicy(profile(event.getConnection())).isEmpty()) {
            event.kickMessage(denied);
        }
    }

    private Optional<Policy> allowedPolicy(PlayerProfile profile) {
        UUID uuid = profile == null ? null : profile.getId();
        if (!ready.getAsBoolean() || uuid == null) return Optional.empty();
        return policies.apply(uuid).filter(policy -> uuid.equals(policy.minecraftUuid())
            && policy.allows(serverId, clock.instant()));
    }

    private static PlayerProfile profile(PlayerConnection connection) {
        if (connection instanceof PlayerLoginConnection login) return login.getAuthenticatedProfile();
        if (connection instanceof PlayerConfigurationConnection configuration) return configuration.getProfile();
        return null; // Unknown future phases must not inherit an unrelated cached identity.
    }
}
