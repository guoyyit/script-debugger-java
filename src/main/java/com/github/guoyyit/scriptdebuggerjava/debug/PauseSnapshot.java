package com.github.guoyyit.scriptdebuggerjava.debug;

import com.github.guoyyit.scriptdebuggerjava.lang.FrameSnapshot;

import java.util.List;

/**
 * Immutable view of the suspended execution: why it stopped, the current
 * statement line and the full call stack with local variables.
 */
public record PauseSnapshot(PauseReason reason, int line, List<FrameSnapshot> callStack,
                            String errorMessage) {
}
