package io.github.underconnor.passport.paper;

import com.destroystokyo.paper.profile.PlayerProfile;
import io.github.underconnor.passport.core.Policy;
import io.github.underconnor.passport.core.PolicyCache;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerConnection;
import io.papermc.paper.connection.PlayerLoginConnection;
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent;
import io.papermc.paper.event.player.PlayerServerFullCheckEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class LoginAdmissionTest {
    private final UUID uuid = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final Instant now = Instant.parse("2026-10-03T09:00:00Z");
    private final Component full = Component.translatable("multiplayer.disconnect.server_full");
    private final Component denied = Component.text("Passport policy denied");
    private final PolicyCache policies = new PolicyCache();
    private final AtomicBoolean ready = new AtomicBoolean(true);

    private LoginAdmission admission(Instant time) {
        return new LoginAdmission(ready::get, policies::get, "survival", denied, Clock.fixed(time, ZoneOffset.UTC));
    }

    private Policy policy(boolean administrator, String status, Set<String> servers, long version) {
        return new Policy(uuid, status, servers, "", "", version, now, now.plusSeconds(60),
            false, null, administrator, Map.of());
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, Map<String, Object> values) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            if (values.containsKey(method.getName())) return values.get(method.getName());
            throw new AssertionError("Unexpected identity/permission access: " + method.getName());
        });
    }

    private PlayerProfile profile(UUID id) {
        return stub(PlayerProfile.class, java.util.Collections.singletonMap("getId", id));
    }

    private PlayerLoginConnection login(UUID id) {
        return stub(PlayerLoginConnection.class, Map.of("getAuthenticatedProfile", profile(id)));
    }

    private PlayerConfigurationConnection configuration(UUID id) {
        return stub(PlayerConfigurationConnection.class, Map.of("getProfile", profile(id)));
    }

    private PlayerServerFullCheckEvent fullEvent(UUID id) {
        return new PlayerServerFullCheckEvent(profile(id), full, true);
    }

    @Test void onlyCurrentAuthorizedAdministratorCanBypassTheCapacityDecision() {
        policies.accept(policy(true, "active", Set.of("survival"), 1));
        PlayerServerFullCheckEvent event = fullEvent(uuid);
        admission(now).fullServer(event);
        assertTrue(event.isAllowed());
        assertEquals(full, event.kickMessage());

        policies.accept(policy(false, "active", Set.of("survival"), 2));
        event = fullEvent(uuid);
        admission(now).fullServer(event);
        assertFalse(event.isAllowed());
        // Ordinary authorized users still pass normal validation when a slot is available.
        PlayerConnectionValidateLoginEvent ordinary = new PlayerConnectionValidateLoginEvent(login(uuid), null);
        admission(now).validateLogin(ordinary);
        assertTrue(ordinary.isAllowed());
    }

    @Test void administratorWithoutCurrentServerScopeOrActiveStatusNeverBypasses() {
        long version = 1;
        for (String status : new String[]{"unlinked", "pending", "suspended", "revoked", "stale"}) {
            policies.accept(policy(true, status, Set.of(), version++));
            PlayerServerFullCheckEvent event = fullEvent(uuid);
            admission(now).fullServer(event);
            assertFalse(event.isAllowed(), status);
        }
        policies.accept(policy(true, "active", Set.of("lobby"), version));
        PlayerServerFullCheckEvent event = fullEvent(uuid);
        admission(now).fullServer(event);
        assertFalse(event.isAllowed());
    }

    @Test void missingExpiredAndUnreadyPoliciesFailClosed() {
        PlayerServerFullCheckEvent missing = fullEvent(uuid);
        admission(now).fullServer(missing);
        assertFalse(missing.isAllowed());
        policies.accept(policy(true, "active", Set.of("survival"), 1));
        PlayerServerFullCheckEvent expired = fullEvent(uuid);
        admission(now.plusSeconds(60)).fullServer(expired);
        assertFalse(expired.isAllowed());
        ready.set(false);
        PlayerServerFullCheckEvent stopping = fullEvent(uuid);
        admission(now).fullServer(stopping);
        assertFalse(stopping.isAllowed());
    }

    @Test void unknownUuidAndMissingProfileIdCannotUseAnotherCachedAdministrator() {
        policies.accept(policy(true, "active", Set.of("survival"), 1));
        for (UUID id : new UUID[]{UUID.randomUUID(), null}) {
            PlayerServerFullCheckEvent event = fullEvent(id);
            admission(now).fullServer(event);
            assertFalse(event.isAllowed());
        }
        LoginAdmission mismatched = new LoginAdmission(ready::get, ignored -> policies.get(uuid),
            "survival", denied, Clock.fixed(now, ZoneOffset.UTC));
        PlayerServerFullCheckEvent other = fullEvent(UUID.randomUUID());
        mismatched.fullServer(other);
        assertFalse(other.isAllowed());
    }

    @Test void validationUsesAuthenticatedLoginAndConfigurationProfilesWithoutOnlinePlayerLookup() {
        policies.accept(policy(true, "active", Set.of("survival"), 1));
        for (PlayerConnection connection : new PlayerConnection[]{login(uuid), configuration(uuid)}) {
            PlayerConnectionValidateLoginEvent event = new PlayerConnectionValidateLoginEvent(connection, null);
            admission(now).validateLogin(event);
            assertTrue(event.isAllowed());
        }
        PlayerConnectionValidateLoginEvent other = new PlayerConnectionValidateLoginEvent(configuration(UUID.randomUUID()), null);
        admission(now).validateLogin(other);
        assertEquals(denied, other.getKickMessage());
    }

    @Test void unknownPhaseAndUnauthenticatedOrIncompleteProfilesAreDenied() {
        policies.accept(policy(true, "active", Set.of("survival"), 1));
        PlayerLoginConnection unauthenticated = stub(PlayerLoginConnection.class,
            java.util.Collections.singletonMap("getAuthenticatedProfile", null));
        for (PlayerConnection connection : new PlayerConnection[]{stub(PlayerConnection.class, Map.of()), unauthenticated, login(null)}) {
            PlayerConnectionValidateLoginEvent event = new PlayerConnectionValidateLoginEvent(connection, null);
            admission(now).validateLogin(event);
            assertFalse(event.isAllowed());
            assertEquals(denied, event.getKickMessage());
        }
    }

    @Test void validationNeverClearsOrReplacesBansWhitelistFullOrOtherPluginKicks() {
        policies.accept(policy(true, "active", Set.of("survival"), 1));
        for (Component reason : new Component[]{full, Component.text("ban"), Component.text("whitelist"), Component.text("plugin denied")}) {
            PlayerConnectionValidateLoginEvent event = new PlayerConnectionValidateLoginEvent(login(uuid), reason);
            admission(now).validateLogin(event);
            assertFalse(event.isAllowed());
            assertSame(reason, event.getKickMessage());
        }
    }

    @Test void revocationOrExpiryDuringConfigurationIsCheckedAgain() {
        policies.accept(policy(true, "active", Set.of("survival"), 1));
        PlayerServerFullCheckEvent fullCheck = fullEvent(uuid);
        admission(now).fullServer(fullCheck);
        assertTrue(fullCheck.isAllowed());
        PlayerConnectionValidateLoginEvent expired = new PlayerConnectionValidateLoginEvent(configuration(uuid), null);
        admission(now.plusSeconds(60)).validateLogin(expired);
        assertFalse(expired.isAllowed());
        policies.accept(policy(true, "revoked", Set.of(), 2));
        assertFalse(policies.accept(policy(true, "active", Set.of("survival"), 1)));
        PlayerConnectionValidateLoginEvent revoked = new PlayerConnectionValidateLoginEvent(configuration(uuid), null);
        admission(now).validateLogin(revoked);
        assertFalse(revoked.isAllowed());
        PlayerServerFullCheckEvent retry = fullEvent(uuid);
        admission(now).fullServer(retry);
        assertFalse(retry.isAllowed());
    }

    @Test void anotherPluginsLaterCapacityDenialIsNotClearedByLoginValidation() throws Exception {
        assertEquals(EventPriority.LOWEST, LoginAdmission.class.getMethod("fullServer", PlayerServerFullCheckEvent.class)
            .getAnnotation(EventHandler.class).priority());
        policies.accept(policy(true, "active", Set.of("survival"), 1));
        PlayerServerFullCheckEvent event = fullEvent(uuid);
        admission(now).fullServer(event);
        Component laterDenial = Component.text("reserved capacity unavailable");
        event.deny(laterDenial);
        PlayerConnectionValidateLoginEvent validation = new PlayerConnectionValidateLoginEvent(login(uuid), event.kickMessage());
        admission(now).validateLogin(validation);
        assertSame(laterDenial, validation.getKickMessage());
        assertFalse(validation.isAllowed());
    }

    @Test void nonFullEventDoesNotAcquireOrMutateAnyPermission() {
        LoginAdmission neverRead = new LoginAdmission(() -> true, ignored -> { fail("Unexpected capacity lookup"); return Optional.empty(); },
            "survival", denied, Clock.fixed(now, ZoneOffset.UTC));
        PlayerServerFullCheckEvent event = new PlayerServerFullCheckEvent(profile(uuid), full, false);
        neverRead.fullServer(event);
        assertTrue(event.isAllowed());
    }
}
