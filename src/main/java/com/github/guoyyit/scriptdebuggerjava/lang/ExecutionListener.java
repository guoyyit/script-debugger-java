package com.github.guoyyit.scriptdebuggerjava.lang;

import java.util.List;

/**
 * Callback invoked by the interpreter before every statement executes.
 * Implementations may block (to pause execution) or throw to abort it.
 */
public interface ExecutionListener {
    void beforeStatement(int line, List<FrameSnapshot> callStack);
}
