package com.github.guoyyit.scriptdebuggerjava.runtime;

/** Read-only view of one call-stack frame, used for debug snapshots. */
public record FrameView(String functionName, int line, Environment env) {
}
