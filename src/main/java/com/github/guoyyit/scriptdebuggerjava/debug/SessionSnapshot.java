package com.github.guoyyit.scriptdebuggerjava.debug;

import java.util.List;

/** Immutable point-in-time view of a debug session. */
public record SessionSnapshot(
        String sessionId,
        SessionState state,
        PauseReason pauseReason,
        TerminationReason terminationReason,
        Integer currentLine,
        List<FrameSnapshot> callStack,
        List<String> output,
        ErrorInfo error,
        String conditionError,
        long executedSteps) {
}
