package com.github.guoyyit.scriptdebuggerjava.lang;

/** A lexical token with its 1-based source line. */
public record Token(TokenType type, String text, int line) {
}
