package com.github.guoyyit.scriptdebuggerjava.debug;

/** Raised when a session id does not exist. */
public class SessionNotFoundException extends RuntimeException {
    public SessionNotFoundException(String sessionId) {
        super("no such debug session: " + sessionId);
    }
}
