package com.github.guoyyit.scriptdebuggerjava.lang;

import com.github.guoyyit.scriptdebuggerjava.lang.ast.Expr;
import com.github.guoyyit.scriptdebuggerjava.lang.ast.Stmt;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser.
 *
 * <pre>
 * program     := statement* EOF
 * statement   := letStmt | assignStmt | ifStmt | whileStmt | fnDef | returnStmt | printStmt | exprStmt
 * letStmt     := "let" IDENT "=" expression
 * assignStmt  := IDENT "=" expression
 * ifStmt      := "if" expression block ("else" (block | ifStmt))?
 * whileStmt   := "while" expression block
 * fnDef       := "fn" IDENT "(" params? ")" block
 * returnStmt  := "return" expression?
 * printStmt   := "print" "(" args? ")"
 * block       := "{" statement* "}"
 * expression  := or
 * or          := and (("or" | "||") and)*
 * and         := equality (("and" | "&&") equality)*
 * equality    := comparison (("==" | "!=") comparison)*
 * comparison  := term (("<" | "<=" | ">" | ">=") term)*
 * term        := factor (("+" | "-") factor)*
 * factor      := unary (("*" | "/" | "%") unary)*
 * unary       := ("not" | "!" | "-") unary | call
 * call        := primary ("(" args? ")")*
 * primary     := NUMBER | "true" | "false" | IDENT | "(" expression ")"
 * </pre>
 *
 * Statements are terminated by newlines or ';'.
 */
public final class Parser {

    private List<Token> tokens;
    private int pos;

    /** Parses a whole script into top-level statements. */
    public List<Stmt> parse(String source) {
        init(source);
        List<Stmt> program = new ArrayList<>();
        skipTerminators();
        while (!check(TokenType.EOF)) {
            program.add(statement());
            expectStatementEnd();
        }
        return program;
    }

    /** Parses a single expression (used for conditional breakpoints). */
    public Expr parseExpression(String source) {
        init(source);
        skipTerminators();
        Expr expr = expression();
        skipTerminators();
        if (!check(TokenType.EOF)) {
            throw error(peek(), "unexpected input after expression: '" + peek().text() + "'");
        }
        return expr;
    }

    private void init(String source) {
        this.tokens = new Lexer().tokenize(source);
        this.pos = 0;
    }

    // ---------- statements ----------

    private Stmt statement() {
        Token t = peek();
        return switch (t.type()) {
            case LET -> letStatement();
            case IF -> ifStatement();
            case WHILE -> whileStatement();
            case FN -> functionDef();
            case RETURN -> returnStatement();
            case PRINT -> printStatement();
            default -> {
                if (t.type() == TokenType.IDENTIFIER && peekNext().type() == TokenType.ASSIGN) {
                    yield assignStatement();
                }
                Expr expr = expression();
                yield new Stmt.ExprStmt(expr, expr.line());
            }
        };
    }

    private Stmt letStatement() {
        Token let = advance(); // let
        Token name = expect(TokenType.IDENTIFIER, "expected variable name after 'let'");
        expect(TokenType.ASSIGN, "expected '=' after variable name");
        Expr init = expression();
        return new Stmt.LetStmt(name.text(), init, let.line());
    }

    private Stmt assignStatement() {
        Token name = expect(TokenType.IDENTIFIER, "expected variable name");
        expect(TokenType.ASSIGN, "expected '='");
        Expr value = expression();
        return new Stmt.AssignStmt(name.text(), value, name.line());
    }

    private Stmt ifStatement() {
        Token ifToken = advance(); // if
        Expr condition = expression();
        List<Stmt> thenBody = block();
        List<Stmt> elseBody = List.of();
        // An 'else' may start on the line after '}'. Only consume the
        // intervening terminators when an 'else' actually follows, so the
        // caller can still see the statement end otherwise.
        int ahead = pos;
        while (ahead < tokens.size()
                && (tokens.get(ahead).type() == TokenType.NEWLINE || tokens.get(ahead).type() == TokenType.SEMICOLON)) {
            ahead++;
        }
        if (ahead < tokens.size() && tokens.get(ahead).type() == TokenType.ELSE) {
            pos = ahead + 1;
            skipTerminators();
            if (check(TokenType.IF)) {
                elseBody = List.of(ifStatement());
            } else {
                elseBody = block();
            }
        }
        return new Stmt.IfStmt(condition, thenBody, elseBody, ifToken.line());
    }

    private Stmt whileStatement() {
        Token whileToken = advance(); // while
        Expr condition = expression();
        List<Stmt> body = block();
        return new Stmt.WhileStmt(condition, body, whileToken.line());
    }

    private Stmt functionDef() {
        Token fn = advance(); // fn
        Token name = expect(TokenType.IDENTIFIER, "expected function name after 'fn'");
        expect(TokenType.LPAREN, "expected '(' after function name");
        List<String> params = new ArrayList<>();
        if (!check(TokenType.RPAREN)) {
            params.add(expect(TokenType.IDENTIFIER, "expected parameter name").text());
            while (match(TokenType.COMMA)) {
                params.add(expect(TokenType.IDENTIFIER, "expected parameter name").text());
            }
        }
        expect(TokenType.RPAREN, "expected ')' after parameters");
        List<Stmt> body = block();
        return new Stmt.FunctionDef(name.text(), params, body, fn.line());
    }

    private Stmt returnStatement() {
        Token ret = advance(); // return
        Expr value = null;
        if (!check(TokenType.NEWLINE) && !check(TokenType.SEMICOLON)
                && !check(TokenType.RBRACE) && !check(TokenType.EOF)) {
            value = expression();
        }
        return new Stmt.ReturnStmt(value, ret.line());
    }

    private Stmt printStatement() {
        Token print = advance(); // print
        expect(TokenType.LPAREN, "expected '(' after 'print'");
        List<Expr> args = arguments();
        return new Stmt.ExprStmt(new Expr.Call("print", args, print.line()), print.line());
    }

    private List<Expr> arguments() {
        List<Expr> args = new ArrayList<>();
        if (!check(TokenType.RPAREN)) {
            args.add(expression());
            while (match(TokenType.COMMA)) {
                args.add(expression());
            }
        }
        expect(TokenType.RPAREN, "expected ')' after arguments");
        return args;
    }

    private List<Stmt> block() {
        skipTerminators();
        expect(TokenType.LBRACE, "expected '{' to start a block");
        skipTerminators();
        List<Stmt> body = new ArrayList<>();
        while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
            body.add(statement());
            expectStatementEnd();
        }
        expect(TokenType.RBRACE, "expected '}' to close a block");
        return body;
    }

    // ---------- expressions ----------

    private Expr expression() {
        return orExpr();
    }

    private Expr orExpr() {
        Expr left = andExpr();
        while (check(TokenType.OR)) {
            Token op = advance();
            Expr right = andExpr();
            left = new Expr.Binary(left, TokenType.OR, right, op.line());
        }
        return left;
    }

    private Expr andExpr() {
        Expr left = equality();
        while (check(TokenType.AND)) {
            Token op = advance();
            Expr right = equality();
            left = new Expr.Binary(left, TokenType.AND, right, op.line());
        }
        return left;
    }

    private Expr equality() {
        Expr left = comparison();
        while (check(TokenType.EQ) || check(TokenType.NEQ)) {
            Token op = advance();
            Expr right = comparison();
            left = new Expr.Binary(left, op.type(), right, op.line());
        }
        return left;
    }

    private Expr comparison() {
        Expr left = term();
        while (check(TokenType.LT) || check(TokenType.LTE) || check(TokenType.GT) || check(TokenType.GTE)) {
            Token op = advance();
            Expr right = term();
            left = new Expr.Binary(left, op.type(), right, op.line());
        }
        return left;
    }

    private Expr term() {
        Expr left = factor();
        while (check(TokenType.PLUS) || check(TokenType.MINUS)) {
            Token op = advance();
            Expr right = factor();
            left = new Expr.Binary(left, op.type(), right, op.line());
        }
        return left;
    }

    private Expr factor() {
        Expr left = unary();
        while (check(TokenType.STAR) || check(TokenType.SLASH) || check(TokenType.PERCENT)) {
            Token op = advance();
            Expr right = unary();
            left = new Expr.Binary(left, op.type(), right, op.line());
        }
        return left;
    }

    private Expr unary() {
        if (check(TokenType.NOT) || check(TokenType.MINUS)) {
            Token op = advance();
            Expr operand = unary();
            return new Expr.Unary(op.type(), operand, op.line());
        }
        return call();
    }

    private Expr call() {
        Expr expr = primary();
        while (check(TokenType.LPAREN)) {
            Token paren = advance();
            if (!(expr instanceof Expr.Variable variable)) {
                throw error(paren, "only named functions can be called");
            }
            List<Expr> args = arguments();
            expr = new Expr.Call(variable.name(), args, paren.line());
        }
        return expr;
    }

    private Expr primary() {
        Token t = peek();
        switch (t.type()) {
            case NUMBER -> {
                advance();
                return new Expr.NumberLiteral(Double.parseDouble(t.text()), t.line());
            }
            case TRUE -> {
                advance();
                return new Expr.BoolLiteral(true, t.line());
            }
            case FALSE -> {
                advance();
                return new Expr.BoolLiteral(false, t.line());
            }
            case IDENTIFIER -> {
                advance();
                return new Expr.Variable(t.text(), t.line());
            }
            case LPAREN -> {
                advance();
                Expr expr = expression();
                expect(TokenType.RPAREN, "expected ')' after expression");
                return expr;
            }
            default -> throw error(t, "expected expression, found '" + t.text() + "'");
        }
    }

    // ---------- token helpers ----------

    private void skipTerminators() {
        while (check(TokenType.NEWLINE) || check(TokenType.SEMICOLON)) {
            pos++;
        }
    }

    private void expectStatementEnd() {
        if (check(TokenType.NEWLINE) || check(TokenType.SEMICOLON)) {
            skipTerminators();
            return;
        }
        if (check(TokenType.RBRACE) || check(TokenType.EOF)) {
            return;
        }
        throw error(peek(), "expected end of statement, found '" + peek().text() + "'");
    }

    private boolean check(TokenType type) {
        return peek().type() == type;
    }

    private boolean match(TokenType type) {
        if (check(type)) {
            pos++;
            return true;
        }
        return false;
    }

    private Token advance() {
        return tokens.get(pos++);
    }

    private Token peek() {
        return tokens.get(pos);
    }

    private Token peekNext() {
        return pos + 1 < tokens.size() ? tokens.get(pos + 1) : tokens.get(tokens.size() - 1);
    }

    private Token expect(TokenType type, String message) {
        if (!check(type)) {
            throw error(peek(), message);
        }
        return advance();
    }

    private ParseException error(Token token, String message) {
        return new ParseException(message, token.line());
    }
}
