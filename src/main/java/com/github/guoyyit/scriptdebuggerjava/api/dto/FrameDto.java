package com.github.guoyyit.scriptdebuggerjava.api.dto;

import java.util.Map;

/**
 * One call frame in a pause snapshot.
 */
public record FrameDto(String function, int line, Map<String, Object> locals) {
}
