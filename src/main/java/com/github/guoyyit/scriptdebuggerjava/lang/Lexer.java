package com.github.guoyyit.scriptdebuggerjava.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Splits script source into tokens. Newlines are significant (statement terminators). */
public final class Lexer {

    private static final Map<String, TokenType> KEYWORDS = Map.ofEntries(
            Map.entry("let", TokenType.LET),
            Map.entry("if", TokenType.IF),
            Map.entry("else", TokenType.ELSE),
            Map.entry("while", TokenType.WHILE),
            Map.entry("fn", TokenType.FN),
            Map.entry("return", TokenType.RETURN),
            Map.entry("print", TokenType.PRINT),
            Map.entry("true", TokenType.TRUE),
            Map.entry("false", TokenType.FALSE),
            Map.entry("and", TokenType.AND),
            Map.entry("or", TokenType.OR),
            Map.entry("not", TokenType.NOT));

    private String source;
    private int pos;
    private int line;
    private List<Token> tokens;

    public List<Token> tokenize(String source) {
        this.source = source;
        this.pos = 0;
        this.line = 1;
        this.tokens = new ArrayList<>();
        while (!isAtEnd()) {
            scanToken();
        }
        tokens.add(new Token(TokenType.EOF, "", line));
        return tokens;
    }

    private void scanToken() {
        char c = advance();
        switch (c) {
            case ' ', '\t', '\r' -> {
            }
            case '\n' -> {
                tokens.add(new Token(TokenType.NEWLINE, "\n", line));
                line++;
            }
            case '#' -> skipComment();
            case '/' -> {
                if (peek() == '/') {
                    skipComment();
                } else {
                    tokens.add(new Token(TokenType.SLASH, "/", line));
                }
            }
            case '(' -> add(TokenType.LPAREN, "(");
            case ')' -> add(TokenType.RPAREN, ")");
            case '{' -> add(TokenType.LBRACE, "{");
            case '}' -> add(TokenType.RBRACE, "}");
            case ',' -> add(TokenType.COMMA, ",");
            case ';' -> add(TokenType.SEMICOLON, ";");
            case '+' -> add(TokenType.PLUS, "+");
            case '-' -> add(TokenType.MINUS, "-");
            case '*' -> add(TokenType.STAR, "*");
            case '%' -> add(TokenType.PERCENT, "%");
            case '=' -> add(match('=') ? TokenType.EQ : TokenType.ASSIGN, "=");
            case '!' -> add(match('=') ? TokenType.NEQ : TokenType.NOT, "!");
            case '<' -> add(match('=') ? TokenType.LTE : TokenType.LT, "<");
            case '>' -> add(match('=') ? TokenType.GTE : TokenType.GT, ">");
            case '&' -> {
                if (match('&')) {
                    add(TokenType.AND, "&&");
                } else {
                    throw error("unexpected character '&' (did you mean '&&'?)");
                }
            }
            case '|' -> {
                if (match('|')) {
                    add(TokenType.OR, "||");
                } else {
                    throw error("unexpected character '|' (did you mean '||'?)");
                }
            }
            default -> {
                if (isDigit(c)) {
                    number(c);
                } else if (isAlpha(c)) {
                    identifier(c);
                } else {
                    throw error("unexpected character '" + c + "'");
                }
            }
        }
    }

    private void number(char first) {
        StringBuilder sb = new StringBuilder().append(first);
        while (!isAtEnd() && isDigit(peek())) {
            sb.append(advance());
        }
        if (!isAtEnd() && peek() == '.' && pos + 1 < source.length() && isDigit(source.charAt(pos + 1))) {
            sb.append(advance());
            while (!isAtEnd() && isDigit(peek())) {
                sb.append(advance());
            }
        }
        tokens.add(new Token(TokenType.NUMBER, sb.toString(), line));
    }

    private void identifier(char first) {
        StringBuilder sb = new StringBuilder().append(first);
        while (!isAtEnd() && isAlphaNumeric(peek())) {
            sb.append(advance());
        }
        String text = sb.toString();
        TokenType type = KEYWORDS.getOrDefault(text, TokenType.IDENTIFIER);
        tokens.add(new Token(type, text, line));
    }

    private void skipComment() {
        while (!isAtEnd() && peek() != '\n') {
            pos++;
        }
    }

    private void add(TokenType type, String text) {
        tokens.add(new Token(type, text, line));
    }

    private boolean match(char expected) {
        if (isAtEnd() || source.charAt(pos) != expected) {
            return false;
        }
        pos++;
        return true;
    }

    private char peek() {
        return isAtEnd() ? '\0' : source.charAt(pos);
    }

    private char advance() {
        return source.charAt(pos++);
    }

    private boolean isAtEnd() {
        return pos >= source.length();
    }

    private boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private boolean isAlphaNumeric(char c) {
        return isAlpha(c) || isDigit(c);
    }

    private ParseException error(String message) {
        return new ParseException(message, line);
    }
}
