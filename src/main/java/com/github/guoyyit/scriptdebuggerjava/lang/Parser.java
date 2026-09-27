package com.github.guoyyit.scriptdebuggerjava.lang;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive descent parser producing a list of statements.
 *
 * <pre>
 * program     := statement* EOF
 * statement   := letStmt | fnStmt | ifStmt | whileStmt | returnStmt | block | exprStmt
 * letStmt     := "let" IDENT "=" expression ";"
 * fnStmt      := "fn" IDENT "(" params? ")" block
 * ifStmt      := "if" "(" expression ")" statement ("else" statement)?
 * whileStmt   := "while" "(" expression ")" statement
 * returnStmt  := "return" expression? ";"
 * block       := "{" statement* "}"
 * exprStmt    := expression ";"
 * expression  := assignment
 * assignment  := IDENT "=" assignment | logicOr
 * logicOr     := logicAnd ("||" logicAnd)*
 * logicAnd    := equality ("&&" equality)*
 * equality    := comparison (("=="|"!=") comparison)*
 * comparison  := term (("<"|"<="|">"|">=") term)*
 * term        := factor (("+"|"-") factor)*
 * factor      := unary (("*"|"/"|"%") unary)*
 * unary       := ("!"|"-") unary | call
 * call        := primary ("(" arguments? ")")*
 * primary     := NUMBER | STRING | "true" | "false" | "nil" | IDENT | "(" expression ")"
 * </pre>
 */
public class Parser {

    public static class ParseException extends RuntimeException {
        private final int line;

        public ParseException(String message, int line) {
            super(message);
            this.line = line;
        }

        public int line() {
            return line;
        }
    }

    private List<Token> tokens;
    private int current;

    public List<Stmt> parse(List<Token> tokens) {
        this.tokens = tokens;
        this.current = 0;
        List<Stmt> statements = new ArrayList<>();
        while (!isAtEnd()) {
            statements.add(statement());
        }
        return statements;
    }

    /**
     * Parses a single expression (used for breakpoint conditions).
     */
    public Expr parseExpression(List<Token> tokens) {
        this.tokens = tokens;
        this.current = 0;
        Expr expr = expression();
        if (!isAtEnd()) {
            throw error(peek(), "unexpected token after expression");
        }
        return expr;
    }

    private Stmt statement() {
        if (match(TokenType.LET)) {
            return letStatement();
        }
        if (match(TokenType.FN)) {
            return fnStatement();
        }
        if (match(TokenType.IF)) {
            return ifStatement();
        }
        if (match(TokenType.WHILE)) {
            return whileStatement();
        }
        if (match(TokenType.RETURN)) {
            return returnStatement();
        }
        if (match(TokenType.LBRACE)) {
            return new Stmt.BlockStmt(block(), previous().line());
        }
        return exprStatement();
    }

    private Stmt letStatement() {
        Token name = consume(TokenType.IDENTIFIER, "expected variable name after 'let'");
        consume(TokenType.ASSIGN, "expected '=' after variable name");
        Expr initializer = expression();
        consume(TokenType.SEMICOLON, "expected ';' after variable declaration");
        return new Stmt.LetStmt(name.lexeme(), initializer, name.line());
    }

    private Stmt fnStatement() {
        Token name = consume(TokenType.IDENTIFIER, "expected function name after 'fn'");
        consume(TokenType.LPAREN, "expected '(' after function name");
        List<String> params = new ArrayList<>();
        if (!check(TokenType.RPAREN)) {
            do {
                params.add(consume(TokenType.IDENTIFIER, "expected parameter name").lexeme());
            } while (match(TokenType.COMMA));
        }
        consume(TokenType.RPAREN, "expected ')' after parameters");
        consume(TokenType.LBRACE, "expected '{' before function body");
        List<Stmt> body = block();
        return new Stmt.FnStmt(name.lexeme(), List.copyOf(params), body, name.line());
    }

    private Stmt ifStatement() {
        Token keyword = previous();
        consume(TokenType.LPAREN, "expected '(' after 'if'");
        Expr condition = expression();
        consume(TokenType.RPAREN, "expected ')' after if condition");
        Stmt thenBranch = statement();
        Stmt elseBranch = null;
        if (match(TokenType.ELSE)) {
            elseBranch = statement();
        }
        return new Stmt.IfStmt(condition, thenBranch, elseBranch, keyword.line());
    }

    private Stmt whileStatement() {
        Token keyword = previous();
        consume(TokenType.LPAREN, "expected '(' after 'while'");
        Expr condition = expression();
        consume(TokenType.RPAREN, "expected ')' after while condition");
        Stmt body = statement();
        return new Stmt.WhileStmt(condition, body, keyword.line());
    }

    private Stmt returnStatement() {
        Token keyword = previous();
        Expr value = null;
        if (!check(TokenType.SEMICOLON)) {
            value = expression();
        }
        consume(TokenType.SEMICOLON, "expected ';' after return value");
        return new Stmt.ReturnStmt(value, keyword.line());
    }

    private List<Stmt> block() {
        List<Stmt> statements = new ArrayList<>();
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            statements.add(statement());
        }
        consume(TokenType.RBRACE, "expected '}' after block");
        return List.copyOf(statements);
    }

    private Stmt exprStatement() {
        Expr expr = expression();
        consume(TokenType.SEMICOLON, "expected ';' after expression");
        return new Stmt.ExprStmt(expr, expr.line());
    }

    private Expr expression() {
        return assignment();
    }

    private Expr assignment() {
        Expr expr = logicOr();
        if (match(TokenType.ASSIGN)) {
            Token equals = previous();
            Expr value = assignment();
            if (expr instanceof Expr.Variable variable) {
                return new Expr.Assign(variable.name(), value, equals.line());
            }
            throw error(equals, "invalid assignment target");
        }
        return expr;
    }

    private Expr logicOr() {
        Expr expr = logicAnd();
        while (match(TokenType.OR)) {
            Token operator = previous();
            Expr right = logicAnd();
            expr = new Expr.Logical(expr, operator.type(), right, operator.line());
        }
        return expr;
    }

    private Expr logicAnd() {
        Expr expr = equality();
        while (match(TokenType.AND)) {
            Token operator = previous();
            Expr right = equality();
            expr = new Expr.Logical(expr, operator.type(), right, operator.line());
        }
        return expr;
    }

    private Expr equality() {
        Expr expr = comparison();
        while (match(TokenType.EQ, TokenType.NE)) {
            Token operator = previous();
            Expr right = comparison();
            expr = new Expr.Binary(expr, operator.type(), right, operator.line());
        }
        return expr;
    }

    private Expr comparison() {
        Expr expr = term();
        while (match(TokenType.LT, TokenType.LE, TokenType.GT, TokenType.GE)) {
            Token operator = previous();
            Expr right = term();
            expr = new Expr.Binary(expr, operator.type(), right, operator.line());
        }
        return expr;
    }

    private Expr term() {
        Expr expr = factor();
        while (match(TokenType.PLUS, TokenType.MINUS)) {
            Token operator = previous();
            Expr right = factor();
            expr = new Expr.Binary(expr, operator.type(), right, operator.line());
        }
        return expr;
    }

    private Expr factor() {
        Expr expr = unary();
        while (match(TokenType.STAR, TokenType.SLASH, TokenType.PERCENT)) {
            Token operator = previous();
            Expr right = unary();
            expr = new Expr.Binary(expr, operator.type(), right, operator.line());
        }
        return expr;
    }

    private Expr unary() {
        if (match(TokenType.NOT, TokenType.MINUS)) {
            Token operator = previous();
            Expr operand = unary();
            return new Expr.Unary(operator.type(), operand, operator.line());
        }
        return call();
    }

    private Expr call() {
        Expr expr = primary();
        while (match(TokenType.LPAREN)) {
            Token paren = previous();
            List<Expr> arguments = new ArrayList<>();
            if (!check(TokenType.RPAREN)) {
                do {
                    arguments.add(expression());
                } while (match(TokenType.COMMA));
            }
            consume(TokenType.RPAREN, "expected ')' after arguments");
            expr = new Expr.Call(expr, List.copyOf(arguments), paren.line());
        }
        return expr;
    }

    private Expr primary() {
        Token token = advance();
        return switch (token.type()) {
            case NUMBER, STRING -> new Expr.Literal(token.literal(), token.line());
            case TRUE -> new Expr.Literal(Boolean.TRUE, token.line());
            case FALSE -> new Expr.Literal(Boolean.FALSE, token.line());
            case NIL -> new Expr.Literal(null, token.line());
            case IDENTIFIER -> new Expr.Variable(token.lexeme(), token.line());
            case LPAREN -> {
                Expr expr = expression();
                consume(TokenType.RPAREN, "expected ')' after expression");
                yield expr;
            }
            default -> throw error(token, "expected expression, got '" + token.lexeme() + "'");
        };
    }

    private boolean match(TokenType... types) {
        for (TokenType type : types) {
            if (check(type)) {
                advance();
                return true;
            }
        }
        return false;
    }

    private boolean check(TokenType type) {
        return !isAtEnd() && peek().type() == type;
    }

    private Token advance() {
        if (!isAtEnd()) {
            current++;
        }
        return previous();
    }

    private Token consume(TokenType type, String message) {
        if (check(type)) {
            return advance();
        }
        throw error(peek(), message);
    }

    private boolean isAtEnd() {
        return peek().type() == TokenType.EOF;
    }

    private Token peek() {
        return tokens.get(current);
    }

    private Token previous() {
        return tokens.get(current - 1);
    }

    private ParseException error(Token token, String message) {
        return new ParseException(message + " (line " + token.line() + ")", token.line());
    }
}
