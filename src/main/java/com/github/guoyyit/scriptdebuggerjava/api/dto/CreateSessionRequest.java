package com.github.guoyyit.scriptdebuggerjava.api.dto;

import java.util.List;

/**
 * Request to create a debug session. Breakpoints are optional and can also
 * be set later, but always before the session is started.
 */
public record CreateSessionRequest(String script, Long timeoutMs, Long maxSteps,
                                   List<BreakpointDto> breakpoints) {
}
