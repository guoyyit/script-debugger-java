package com.github.guoyyit.scriptdebuggerjava.debug;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Creates, tracks and disposes debug sessions. Sessions are fully independent:
 * each owns its execution thread, interpreter and breakpoint configuration.
 */
@Service
public class SessionManager {

    private final ConcurrentMap<String, DebugSession> sessions = new ConcurrentHashMap<>();

    /** Creates a session, starts its execution thread and returns it. */
    public DebugSession create(SessionConfig config) {
        if (config == null || config.source() == null || config.source().isBlank()) {
            throw new IllegalArgumentException("source must not be empty");
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        DebugSession session = new DebugSession(id, config);
        sessions.put(id, session);
        try {
            session.start();
        } catch (RuntimeException e) {
            sessions.remove(id);
            session.close();
            throw e;
        }
        return session;
    }

    public DebugSession get(String id) {
        DebugSession session = sessions.get(id);
        if (session == null) {
            throw new SessionNotFoundException(id);
        }
        return session;
    }

    public List<DebugSession> list() {
        return List.copyOf(sessions.values());
    }

    /** Terminates and removes the session, releasing its resources. */
    public void remove(String id) {
        DebugSession session = sessions.remove(id);
        if (session == null) {
            throw new SessionNotFoundException(id);
        }
        session.close();
    }
}
