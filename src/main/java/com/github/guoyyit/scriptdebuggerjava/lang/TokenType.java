package com.github.guoyyit.scriptdebuggerjava.lang;

/** Token kinds of the script language subset. */
public enum TokenType {
    IDENTIFIER, NUMBER, TRUE, FALSE,
    LET, IF, ELSE, WHILE, FN, RETURN, PRINT,
    PLUS, MINUS, STAR, SLASH, PERCENT,
    EQ, NEQ, LT, LTE, GT, GTE,
    AND, OR, NOT,
    ASSIGN,
    LPAREN, RPAREN, LBRACE, RBRACE, COMMA, SEMICOLON, NEWLINE,
    EOF
}
