package org.fractalx.runtime;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Validates x-internal-token JWTs and propagates identity into the Spring SecurityContext.
 *
 * Kept in a separate class so NetScopeContextInterceptor has zero direct bytecode references
 * to JJWT or Spring Security. Java loads classes lazily — this class is only loaded when
 * security is enabled and this is actually called. When security is disabled it is never
 * loaded, so JJWT and Spring Security do not need to be on the classpath.
 */
class InternalTokenValidator {

    static void propagate(String internalToken, Logger log) {
        try {
            String secret = resolveInternalJwtSecret();
            javax.crypto.SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer("fractalx-gateway")
                    .build()
                    .parseSignedClaims(internalToken)
                    .getPayload();

            Set<String> audSet = claims.getAudience();
            if (audSet == null || !audSet.contains("fractalx-internal")) {
                log.warn("NetScope: x-internal-token has unexpected audience '{}' — rejecting", audSet);
                return;
            }

            String userId   = claims.getSubject();
            String rolesStr = claims.get("roles", String.class);
            List<SimpleGrantedAuthority> auths = (rolesStr == null || rolesStr.isBlank())
                    ? List.of()
                    : Arrays.stream(rolesStr.split(","))
                            .map(String::trim)
                            .filter(r -> !r.isBlank())
                            .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                            .collect(Collectors.toList());

            String username = claims.get("username", String.class);
            String email    = claims.get("email",    String.class);
            Map<String, Object> attributes = new HashMap<>();
            Set<String> reserved = Set.of("sub", "roles", "iss", "aud", "iat", "exp", "username", "email");
            claims.forEach((k, v) -> { if (!reserved.contains(k)) attributes.put(k, v); });

            GatewayPrincipal principal = new GatewayPrincipal(
                    userId, username, email, auths, Collections.unmodifiableMap(attributes));

            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(principal, internalToken, auths));
            log.debug("NetScope: established Authentication for user={} from x-internal-token", userId);

        } catch (ExpiredJwtException e) {
            log.warn("NetScope: x-internal-token expired for subject={} — request proceeds without auth. " +
                     "Check gateway clock sync or increase token TTL.", e.getClaims().getSubject());
        } catch (Exception e) {
            log.debug("NetScope: x-internal-token validation failed — {}", e.getMessage());
        }
    }

    static void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static String resolveInternalJwtSecret() {
        String sysProp = System.getProperty("fractalx.security.internal-jwt-secret");
        if (sysProp != null && !sysProp.isBlank()) return sysProp;
        String envVar = System.getenv("FRACTALX_INTERNAL_JWT_SECRET");
        if (envVar != null && !envVar.isBlank()) return envVar;
        return "fractalx-internal-secret-change-in-prod-!!";
    }
}
