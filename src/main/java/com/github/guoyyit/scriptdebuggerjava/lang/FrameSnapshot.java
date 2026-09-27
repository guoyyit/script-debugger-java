package com.github.guoyyit.scriptdebuggerjava.lang;

import java.util.Map;

/**
 * Immutable view of one call frame: the function being executed, the current
 * line inside it and its visible local variables.
 */
public record FrameSnapshot(String functionName, int line, Map<String, Object> locals) {
}
