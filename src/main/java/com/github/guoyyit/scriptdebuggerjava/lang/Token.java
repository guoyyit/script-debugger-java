package com.github.guoyyit.scriptdebuggerjava.lang;

/**
 * A lexical token with source line information.
 */
public record Token(TokenType type, String lexeme, Object literal, int line) {
    @Override
    public String toString() {
        return type + " " + lexeme + (literal != null ? " " + literal : "") + " @" + line;
    }
}
