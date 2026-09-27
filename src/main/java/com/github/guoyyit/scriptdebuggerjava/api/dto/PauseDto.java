package com.github.guoyyit.scriptdebuggerjava.api.dto;

import java.util.List;

/**
 * Why and where execution is suspended, with the full call stack.
 */
public record PauseDto(String reason, int line, String errorMessage, List<FrameDto> callStack) {
}
