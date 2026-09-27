package com.github.guoyyit.scriptdebuggerjava.lang;

import java.util.List;

/**
 * Statement AST nodes. Every node carries the source line it originated from.
 */
public sealed interface Stmt {
    int line();

    record ExprStmt(Expr expression, int line) implements Stmt {
    }

    record LetStmt(String name, Expr initializer, int line) implements Stmt {
    }

    record BlockStmt(List<Stmt> statements, int line) implements Stmt {
    }

    record IfStmt(Expr condition, Stmt thenBranch, Stmt elseBranch, int line) implements Stmt {
    }

    record WhileStmt(Expr condition, Stmt body, int line) implements Stmt {
    }

    record FnStmt(String name, List<String> params, List<Stmt> body, int line) implements Stmt {
    }

    record ReturnStmt(Expr value, int line) implements Stmt {
    }
}
