package com.github.guoyyit.scriptdebuggerjava.lang;

import java.util.List;

/**
 * A user defined function: its declaration plus the closure environment.
 */
public class ScriptFunction implements ScriptCallable {

    private final Stmt.FnStmt declaration;
    private final Environment closure;

    public ScriptFunction(Stmt.FnStmt declaration, Environment closure) {
        this.declaration = declaration;
        this.closure = closure;
    }

    public Stmt.FnStmt declaration() {
        return declaration;
    }

    public Environment closure() {
        return closure;
    }

    @Override
    public int arity() {
        return declaration.params().size();
    }

    @Override
    public Object call(Interpreter interpreter, List<Object> arguments, int callLine) {
        return interpreter.invokeFunction(this, arguments, callLine);
    }

    @Override
    public String toString() {
        return "<fn " + declaration.name() + ">";
    }
}
