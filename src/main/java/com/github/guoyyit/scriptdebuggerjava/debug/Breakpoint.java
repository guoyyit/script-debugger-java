package com.github.guoyyit.scriptdebuggerjava.debug;

/** A line breakpoint with an optional condition expression (nullable). */
public record Breakpoint(int line, String condition) {
}
