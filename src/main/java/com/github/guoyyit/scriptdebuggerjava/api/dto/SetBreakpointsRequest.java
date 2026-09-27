package com.github.guoyyit.scriptdebuggerjava.api.dto;

import java.util.List;

/** Request body for replacing the breakpoint list of a session. */
public record SetBreakpointsRequest(List<BreakpointDto> breakpoints) {
}
