package com.github.guoyyit.scriptdebuggerjava.lang.ast;

import java.util.List;

/** Base type for statements. Every node knows its 1-based source line. */
public abstract class Stmt {
    private final int line;

    protected Stmt(int line) {
        this.line = line;
    }

    public int line() {
        return line;
    }

    public static final class LetStmt extends Stmt {
        private final String name;
        private final Expr init;

        public LetStmt(String name, Expr init, int line) {
            super(line);
            this.name = name;
            this.init = init;
        }

        public String name() {
            return name;
        }

        public Expr init() {
            return init;
        }
    }

    public static final class AssignStmt extends Stmt {
        private final String name;
        private final Expr value;

        public AssignStmt(String name, Expr value, int line) {
            super(line);
            this.name = name;
            this.value = value;
        }

        public String name() {
            return name;
        }

        public Expr value() {
            return value;
        }
    }

    public static final class ExprStmt extends Stmt {
        private final Expr expr;

        public ExprStmt(Expr expr, int line) {
            super(line);
            this.expr = expr;
        }

        public Expr expr() {
            return expr;
        }
    }

    public static final class IfStmt extends Stmt {
        private final Expr condition;
        private final List<Stmt> thenBody;
        private final List<Stmt> elseBody;

        public IfStmt(Expr condition, List<Stmt> thenBody, List<Stmt> elseBody, int line) {
            super(line);
            this.condition = condition;
            this.thenBody = thenBody;
            this.elseBody = elseBody;
        }

        public Expr condition() {
            return condition;
        }

        public List<Stmt> thenBody() {
            return thenBody;
        }

        public List<Stmt> elseBody() {
            return elseBody;
        }
    }

    public static final class WhileStmt extends Stmt {
        private final Expr condition;
        private final List<Stmt> body;

        public WhileStmt(Expr condition, List<Stmt> body, int line) {
            super(line);
            this.condition = condition;
            this.body = body;
        }

        public Expr condition() {
            return condition;
        }

        public List<Stmt> body() {
            return body;
        }
    }

    public static final class FunctionDef extends Stmt {
        private final String name;
        private final List<String> params;
        private final List<Stmt> body;

        public FunctionDef(String name, List<String> params, List<Stmt> body, int line) {
            super(line);
            this.name = name;
            this.params = params;
            this.body = body;
        }

        public String name() {
            return name;
        }

        public List<String> params() {
            return params;
        }

        public List<Stmt> body() {
            return body;
        }
    }

    public static final class ReturnStmt extends Stmt {
        private final Expr value; // nullable

        public ReturnStmt(Expr value, int line) {
            super(line);
            this.value = value;
        }

        public Expr value() {
            return value;
        }
    }
}
