package com.github.guoyyit.scriptdebuggerjava.debug;

/**
 * Why a session ended. Distinguishes normal completion from errors,
 * timeouts, step-limit exhaustion and explicit user termination.
 */
public enum TerminationReason {
    COMPLETED,
    ERROR,
    TIMEOUT,
    STEP_LIMIT,
    USER_TERMINATED
}
