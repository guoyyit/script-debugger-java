package com.github.guoyyit.scriptdebuggerjava.debug;

import java.util.Map;

/** Immutable snapshot of one call-stack frame. Locals are JSON-friendly values. */
public record FrameSnapshot(String functionName, int line, Map<String, Object> locals) {
}
