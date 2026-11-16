package MailServer;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** Server-owned bearer sessions. Never use the UDP endpoint as an identity. */
public final class SessionManager {
    private record Session(String owner, long expiresAt) { }
    private final Map<String, Session> sessions = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final long ttlMillis;

    public SessionManager() { this(Clock.systemUTC(), Duration.ofMinutes(30)); }
    public SessionManager(Clock clock, Duration ttl) {
        this.clock = clock;
        this.ttlMillis = ttl.toMillis();
        if (ttlMillis <= 0) throw new IllegalArgumentException("Session TTL must be positive");
    }
    public synchronized String create(String owner) {
        sessions.entrySet().removeIf(e -> e.getValue().expiresAt <= clock.millis());
        if (sessions.size() >= 10_000) throw new IllegalStateException("Session capacity reached");
        byte[] bytes = new byte[32];
        String token;
        do {
            random.nextBytes(bytes);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } while (sessions.containsKey(token));
        sessions.put(token, new Session(owner, Math.addExact(clock.millis(), ttlMillis)));
        return token;
    }
    public synchronized String owner(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
        Session session = sessions.get(token);
        if (session == null) return null;
        if (session.expiresAt <= clock.millis()) { sessions.remove(token); return null; }
        return session.owner;
    }
    public synchronized void revoke(String token) { sessions.remove(token); }
}
