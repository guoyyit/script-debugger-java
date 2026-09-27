package com.github.guoyyit.scriptdebuggerjava.lang;

import java.util.List;

/**
 * A runtime failure during script execution, carrying the script position and
 * a snapshot of the call stack at the point of failure.
 */
public class ScriptRuntimeException extends RuntimeException {
    private final int line;
    private final List<FrameSnapshot> callStack;

    public ScriptRuntimeException(String message, int line, List<FrameSnapshot> callStack) {
        super(message);
        this.line = line;
        this.callStack = callStack;
    }

    public int line() {
        return line;
    }

    public List<FrameSnapshot> callStack() {
        return callStack;
    }
}
