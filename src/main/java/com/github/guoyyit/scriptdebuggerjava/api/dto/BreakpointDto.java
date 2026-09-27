package com.github.guoyyit.scriptdebuggerjava.api.dto;

/**
 * Breakpoint as exchanged over the REST API.
 */
public record BreakpointDto(int line, String condition) {
}
