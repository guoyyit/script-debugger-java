package com.github.guoyyit.scriptdebuggerjava.runtime;

/** Internal control-flow signal aborting execution (timeout, step limit, user stop). */
public class AbortSignal extends RuntimeException {
    public enum Reason { TIMEOUT, STEP_LIMIT, USER }

    private final Reason reason;

    public AbortSignal(Reason reason) {
        super(reason.name(), null, false, false);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
