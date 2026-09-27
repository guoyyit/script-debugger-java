package com.github.guoyyit.scriptdebuggerjava.debug;

/**
 * A breakpoint on a source line, optionally guarded by a boolean script
 * expression that is evaluated when the line is reached.
 */
public record Breakpoint(int line, String condition) {
    public Breakpoint(int line) {
        this(line, null);
    }

    public boolean hasCondition() {
        return condition != null && !condition.isBlank();
    }
}
