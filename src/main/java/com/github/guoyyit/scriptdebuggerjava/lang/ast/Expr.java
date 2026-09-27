package com.github.guoyyit.scriptdebuggerjava.lang.ast;

import com.github.guoyyit.scriptdebuggerjava.lang.TokenType;

import java.util.List;

/** Base type for expressions. Every node knows its 1-based source line. */
public abstract class Expr {
    private final int line;

    protected Expr(int line) {
        this.line = line;
    }

    public int line() {
        return line;
    }

    public static final class NumberLiteral extends Expr {
        private final double value;

        public NumberLiteral(double value, int line) {
            super(line);
            this.value = value;
        }

        public double value() {
            return value;
        }
    }

    public static final class BoolLiteral extends Expr {
        private final boolean value;

        public BoolLiteral(boolean value, int line) {
            super(line);
            this.value = value;
        }

        public boolean value() {
            return value;
        }
    }

    public static final class Variable extends Expr {
        private final String name;

        public Variable(String name, int line) {
            super(line);
            this.name = name;
        }

        public String name() {
            return name;
        }
    }

    public static final class Binary extends Expr {
        private final Expr left;
        private final TokenType op;
        private final Expr right;

        public Binary(Expr left, TokenType op, Expr right, int line) {
            super(line);
            this.left = left;
            this.op = op;
            this.right = right;
        }

        public Expr left() {
            return left;
        }

        public TokenType op() {
            return op;
        }

        public Expr right() {
            return right;
        }
    }

    public static final class Unary extends Expr {
        private final TokenType op;
        private final Expr operand;

        public Unary(TokenType op, Expr operand, int line) {
            super(line);
            this.op = op;
            this.operand = operand;
        }

        public TokenType op() {
            return op;
        }

        public Expr operand() {
            return operand;
        }
    }

    public static final class Call extends Expr {
        private final String name;
        private final List<Expr> args;

        public Call(String name, List<Expr> args, int line) {
            super(line);
            this.name = name;
            this.args = args;
        }

        public String name() {
            return name;
        }

        public List<Expr> args() {
            return args;
        }
    }
}
