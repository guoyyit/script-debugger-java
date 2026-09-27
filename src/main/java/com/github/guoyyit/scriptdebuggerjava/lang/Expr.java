package com.github.guoyyit.scriptdebuggerjava.lang;

import java.util.List;

/**
 * Expression AST nodes. Every node carries the source line it originated from.
 */
public sealed interface Expr {
    int line();

    record Literal(Object value, int line) implements Expr {
    }

    record Variable(String name, int line) implements Expr {
    }

    record Assign(String name, Expr value, int line) implements Expr {
    }

    record Binary(Expr left, TokenType operator, Expr right, int line) implements Expr {
    }

    record Logical(Expr left, TokenType operator, Expr right, int line) implements Expr {
    }

    record Unary(TokenType operator, Expr operand, int line) implements Expr {
    }

    record Call(Expr callee, List<Expr> arguments, int line) implements Expr {
    }
}
