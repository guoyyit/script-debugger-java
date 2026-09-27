package com.github.guoyyit.scriptdebuggerjava.debug;

/**
 * Lifecycle states of a debug session.
 */
public enum SessionState {
    /** Created but not started yet. */
    CREATED,
    /** Script thread is executing (or about to). */
    RUNNING,
    /** Suspended on a breakpoint, step or pause request. */
    PAUSED,
    /** Suspended because a runtime error occurred; user may inspect and resume/terminate. */
    PAUSED_ON_ERROR,
    /** Script finished (normally or after an acknowledged error). */
    FINISHED,
    /** Killed by timeout, step limit or user request. */
    TERMINATED
}
