package com.github.guoyyit.scriptdebuggerjava.debug;

import java.util.List;

/** Configuration for a new debug session. Null limits mean "use default". */
public record SessionConfig(
        String source,
        Long timeoutMs,
        Long maxSteps,
        List<Breakpoint> breakpoints) {
}
