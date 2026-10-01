package io.github.underconnor.passport.api;
import java.util.*;
/** Nonblocking cache only. Empty when offline, expired, or not permitted on this server. */
public interface PassportIdentityService {
    Optional<PassportIdentity> identity(UUID minecraftUuid);
    /** Exact matching; callers must reject an ambiguous result instead of choosing an arbitrary player. */
    List<UUID> resolveOnline(String realNameOrMinecraftName);
}
