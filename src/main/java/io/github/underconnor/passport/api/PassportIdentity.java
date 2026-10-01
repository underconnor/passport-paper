package io.github.underconnor.passport.api;
import java.time.Instant;
import java.util.UUID;
/** Minimal verified identity. No full student number, Discord ID, or school token. */
public record PassportIdentity(UUID minecraftUuid, String realName, boolean member, String admissionYear, Instant expiresAt) {}
