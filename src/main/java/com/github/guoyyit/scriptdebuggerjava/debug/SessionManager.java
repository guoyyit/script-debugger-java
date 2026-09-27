package com.github.guoyyit.scriptdebuggerjava.debug;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates, tracks and cleans up debug sessions. Sessions are fully
 * independent of each other and safe to use concurrently.
 */
@Component
public class SessionManager {

    public static final long DEFAULT_TIMEOUT_MS = 10_000;
    public static final long DEFAULT_MAX_STEPS = 100_000;
    public static final long MAX_TIMEOUT_MS = 300_000;
    public static final long MAX_STEPS_BOUND = 10_000_000;

    private final Map<String, DebugSession> sessions = new ConcurrentHashMap<>();

    public DebugSession create(String script, long timeoutMs, long maxSteps) {
        if (script == null || script.isBlank()) {
            throw new IllegalArgumentException("script must not be empty");
        }
        // 0 means "use the default", a negative value means "no limit".
        if (timeoutMs == 0) {
            timeoutMs = DEFAULT_TIMEOUT_MS;
        } else if (timeoutMs < 0) {
            timeoutMs = 0;
        }
        if (maxSteps == 0) {
            maxSteps = DEFAULT_MAX_STEPS;
        } else if (maxSteps < 0) {
            maxSteps = 0;
        }
        timeoutMs = Math.min(timeoutMs, MAX_TIMEOUT_MS);
        maxSteps = Math.min(maxSteps, MAX_STEPS_BOUND);
        String id = UUID.randomUUID().toString().substring(0, 8);
        DebugSession session = new DebugSession(id, script, timeoutMs, maxSteps);
        sessions.put(id, session);
        return session;
    }

    public DebugSession get(String id) {
        return sessions.get(id);
    }

    /** Destroys the session and releases its resources. */
    public void destroy(String id) {
        DebugSession session = sessions.remove(id);
        if (session != null) {
            session.close();
        }
    }

    public List<DebugSession> list() {
        return List.copyOf(sessions.values());
    }

    public int size() {
        return sessions.size();
    }

    /** Terminates every session and releases all resources. */
    public void shutdown() {
        for (String id : List.copyOf(sessions.keySet())) {
            destroy(id);
        }
    }

    /**
     * Periodic reaper: ended sessions are kept for a grace period so clients
     * can still inspect their final state, then removed.
     */
    @Scheduled(fixedDelay = 30_000)
    public void scheduledReap() {
        reapEndedSessions(ENDED_SESSION_TTL_MS);
    }

    /** How long an ended session remains queryable before being reaped. */
    public static final long ENDED_SESSION_TTL_MS = 60_000;

    /**
     * Reaps sessions that ended more than {@code maxAgeMillis} ago so their
     * state does not accumulate. Invoked periodically by Spring scheduling.
     *
     * @return number of sessions removed
     */
    public int reapEndedSessions(long maxAgeMillis) {
        int removed = 0;
        long now = System.currentTimeMillis();
        for (DebugSession session : list()) {
            long endedAt = session.endedAtMillis();
            if (endedAt > 0 && now - endedAt >= maxAgeMillis) {
                destroy(session.id());
                removed++;
            }
        }
        return removed;
    }
}
