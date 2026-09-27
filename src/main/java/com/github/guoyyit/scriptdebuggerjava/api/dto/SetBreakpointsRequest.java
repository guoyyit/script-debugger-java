package com.github.guoyyit.scriptdebuggerjava.api.dto;

import java.util.List;

/**
 * Replaces all breakpoints of a session.
 */
public record SetBreakpointsRequest(List<BreakpointDto> breakpoints) {
}
