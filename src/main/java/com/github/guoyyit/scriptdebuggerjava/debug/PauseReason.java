package com.github.guoyyit.scriptdebuggerjava.debug;

/**
 * Why execution is currently suspended.
 */
public enum PauseReason {
    BREAKPOINT,
    /** A breakpoint condition failed to evaluate (e.g. undefined variable). */
    CONDITION_ERROR,
    STEP,
    PAUSE_REQUEST,
    ERROR
}
