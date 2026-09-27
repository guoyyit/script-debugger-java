package com.github.guoyyit.scriptdebuggerjava.api.dto;

/** Breakpoint as accepted by the REST API. Condition is optional. */
public record BreakpointDto(int line, String condition) {
}
