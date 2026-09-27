package com.github.guoyyit.scriptdebuggerjava.api.dto;

import java.util.List;

/** Request body for creating a debug session. */
public record StartSessionRequest(
        String source,
        Long timeoutMs,
        Long maxSteps,
        List<BreakpointDto> breakpoints) {
}
