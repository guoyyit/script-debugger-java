package com.github.guoyyit.scriptdebuggerjava.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns script source text into a token stream.
 */
public class Lexer {

    private static final Map<String, TokenType> KEYWORDS = Map.of(
            "let", TokenType.LET,
            "fn", TokenType.FN,
            "if", TokenType.IF,
            "else", TokenType.ELSE,
            "while", TokenType.WHILE,
            "return", TokenType.RETURN,
            "true", TokenType.TRUE,
            "false", TokenType.FALSE,
            "nil", TokenType.NIL);

    private String source;
    private final List<Token> tokens = new ArrayList<>();
    private int start;
    private int current;
    private int line;

    public List<Token> scan(String source) {
        this.source = source;
        this.tokens.clear();
        this.start = 0;
        this.current = 0;
        this.line = 1;
        while (!isAtEnd()) {
            start = current;
            scanToken();
        }
        tokens.add(new Token(TokenType.EOF, "", null, line));
        return List.copyOf(tokens);
    }

    private void scanToken() {
        char c = advance();
        switch (c) {
            case '(' -> add(TokenType.LPAREN);
            case ')' -> add(TokenType.RPAREN);
            case '{' -> add(TokenType.LBRACE);
            case '}' -> add(TokenType.RBRACE);
            case ',' -> add(TokenType.COMMA);
            case ';' -> add(TokenType.SEMICOLON);
            case '+' -> add(TokenType.PLUS);
            case '-' -> add(TokenType.MINUS);
            case '*' -> add(TokenType.STAR);
            case '%' -> add(TokenType.PERCENT);
            case '/' -> {
                if (match('/')) {
                    while (peek() != '\n' && !isAtEnd()) {
                        advance();
                    }
                } else {
                    add(TokenType.SLASH);
                }
            }
            case '=' -> add(match('=') ? TokenType.EQ : TokenType.ASSIGN);
            case '!' -> add(match('=') ? TokenType.NE : TokenType.NOT);
            case '<' -> add(match('=') ? TokenType.LE : TokenType.LT);
            case '>' -> add(match('=') ? TokenType.GE : TokenType.GT);
            case '&' -> {
                if (match('&')) {
                    add(TokenType.AND);
                } else {
                    throw error("unexpected character '&' (did you mean '&&'?)");
                }
            }
            case '|' -> {
                if (match('|')) {
                    add(TokenType.OR);
                } else {
                    throw error("unexpected character '|' (did you mean '||'?)");
                }
            }
            case ' ', '\r', '\t' -> {
                // skip whitespace
            }
            case '\n' -> line++;
            case '"' -> string();
            default -> {
                if (isDigit(c)) {
                    number();
                } else if (isAlpha(c)) {
                    identifier();
                } else {
                    throw error("unexpected character '" + c + "'");
                }
            }
        }
    }

    private void string() {
        StringBuilder sb = new StringBuilder();
        while (peek() != '"' && !isAtEnd()) {
            char c = advance();
            if (c == '\n') {
                line++;
            }
            if (c == '\\' && !isAtEnd()) {
                char next = advance();
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(next);
                }
            } else {
                sb.append(c);
            }
        }
        if (isAtEnd()) {
            throw error("unterminated string");
        }
        advance(); // closing quote
        tokens.add(new Token(TokenType.STRING, source.substring(start, current), sb.toString(), line));
    }

    private void number() {
        while (isDigit(peek())) {
            advance();
        }
        if (peek() == '.' && isDigit(peekNext())) {
            advance();
            while (isDigit(peek())) {
                advance();
            }
        }
        String text = source.substring(start, current);
        tokens.add(new Token(TokenType.NUMBER, text, Double.parseDouble(text), line));
    }

    private void identifier() {
        while (isAlphaNumeric(peek())) {
            advance();
        }
        String text = source.substring(start, current);
        TokenType type = KEYWORDS.getOrDefault(text, TokenType.IDENTIFIER);
        add(type);
    }

    private Parser.ParseException error(String message) {
        return new Parser.ParseException(message + " (line " + line + ")", line);
    }

    private boolean isAtEnd() {
        return current >= source.length();
    }

    private char advance() {
        return source.charAt(current++);
    }

    private boolean match(char expected) {
        if (isAtEnd() || source.charAt(current) != expected) {
            return false;
        }
        current++;
        return true;
    }

    private char peek() {
        return isAtEnd() ? '\0' : source.charAt(current);
    }

    private char peekNext() {
        return current + 1 >= source.length() ? '\0' : source.charAt(current + 1);
    }

    private void add(TokenType type) {
        tokens.add(new Token(type, source.substring(start, current), null, line));
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
}
