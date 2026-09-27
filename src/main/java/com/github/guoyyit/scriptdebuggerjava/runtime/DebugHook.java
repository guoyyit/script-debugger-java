package com.github.guoyyit.scriptdebuggerjava.runtime;

import com.github.guoyyit.scriptdebuggerjava.lang.ast.Stmt;

/** Called by the interpreter before each statement, on the interpreter thread. */
public interface DebugHook {
    void beforeStatement(Stmt stmt, Environment env);

    DebugHook NONE = (stmt, env) -> {
    };
}
