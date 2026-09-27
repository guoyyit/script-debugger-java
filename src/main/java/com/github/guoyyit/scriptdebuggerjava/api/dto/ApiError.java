package com.github.guoyyit.scriptdebuggerjava.api.dto;

/** Error body returned by the REST API. */
public record ApiError(String message, Integer line) {
}
