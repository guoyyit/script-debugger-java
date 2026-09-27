package com.github.guoyyit.scriptdebuggerjava.api.dto;

import java.util.List;

/**
 * Full status of a debug session.
 */
public record SessionStatusResponse(String sessionId, String state, String terminationReason,
                                    String error, PauseDto pause, List<String> stdout,
                                    long stepCount) {
}
