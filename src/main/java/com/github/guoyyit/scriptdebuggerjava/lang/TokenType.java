package com.github.guoyyit.scriptdebuggerjava.lang;

/**
 * Token categories of the script language.
 */
public enum TokenType {
    IDENTIFIER, NUMBER, STRING,
    LET, FN, IF, ELSE, WHILE, RETURN, TRUE, FALSE, NIL,
    PLUS, MINUS, STAR, SLASH, PERCENT,
    ASSIGN, EQ, NE, LT, LE, GT, GE,
    AND, OR, NOT,
    LPAREN, RPAREN, LBRACE, RBRACE, COMMA, SEMICOLON,
    EOF
}
