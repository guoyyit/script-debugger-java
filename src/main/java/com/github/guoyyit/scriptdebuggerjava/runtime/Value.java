package com.github.guoyyit.scriptdebuggerjava.runtime;

import com.github.guoyyit.scriptdebuggerjava.lang.ast.Stmt;

import java.util.List;

/** A runtime value: number, boolean, function (script or builtin) or nil. */
public final class Value {

    public enum Kind { NUMBER, BOOLEAN, FUNCTION, NIL }

    /** A native function implemented in Java. */
    @FunctionalInterface
    public interface BuiltinFunction {
        Value apply(List<Value> args, int line);
    }

    private static final Value NIL = new Value(Kind.NIL, 0, false, null, null, null, null);

    private final Kind kind;
    private final double number;
    private final boolean bool;
    private final Stmt.FunctionDef functionDef;
    private final Environment closure;
    private final String builtinName;
    private final BuiltinFunction builtin;

    private Value(Kind kind, double number, boolean bool, Stmt.FunctionDef functionDef,
                  Environment closure, String builtinName, BuiltinFunction builtin) {
        this.kind = kind;
        this.number = number;
        this.bool = bool;
        this.functionDef = functionDef;
        this.closure = closure;
        this.builtinName = builtinName;
        this.builtin = builtin;
    }

    public static Value of(double number) {
        return new Value(Kind.NUMBER, number, false, null, null, null, null);
    }

    public static Value of(boolean bool) {
        return new Value(Kind.BOOLEAN, 0, bool, null, null, null, null);
    }

    public static Value nil() {
        return NIL;
    }

    public static Value function(Stmt.FunctionDef def, Environment closure) {
        return new Value(Kind.FUNCTION, 0, false, def, closure, null, null);
    }

    public static Value builtin(String name, BuiltinFunction fn) {
        return new Value(Kind.FUNCTION, 0, false, null, null, name, fn);
    }

    public Kind kind() {
        return kind;
    }

    public double asNumber(int line) {
        if (kind != Kind.NUMBER) {
            throw new ScriptRuntimeException("expected number, got " + kindName(), line);
        }
        return number;
    }

    public boolean asBoolean(int line) {
        if (kind != Kind.BOOLEAN) {
            throw new ScriptRuntimeException("expected boolean, got " + kindName(), line);
        }
        return bool;
    }

    public boolean isNumber() {
        return kind == Kind.NUMBER;
    }

    public boolean isBoolean() {
        return kind == Kind.BOOLEAN;
    }

    public boolean isFunction() {
        return kind == Kind.FUNCTION;
    }

    public boolean isBuiltin() {
        return builtin != null;
    }

    public Stmt.FunctionDef functionDef() {
        return functionDef;
    }

    public Environment closure() {
        return closure;
    }

    public String builtinName() {
        return builtinName;
    }

    public BuiltinFunction builtin() {
        return builtin;
    }

    private String kindName() {
        return switch (kind) {
            case NUMBER -> "number";
            case BOOLEAN -> "boolean";
            case FUNCTION -> "function";
            case NIL -> "nil";
        };
    }

    /** Human-readable form used by print and output. */
    public String toDisplay() {
        return switch (kind) {
            case NUMBER -> {
                if (number == Math.rint(number) && !Double.isInfinite(number)) {
                    yield Long.toString((long) number);
                }
                yield Double.toString(number);
            }
            case BOOLEAN -> Boolean.toString(bool);
            case FUNCTION -> builtin != null ? "<builtin " + builtinName + ">" : "<fn " + functionDef.name() + ">";
            case NIL -> "nil";
        };
    }

    /** JSON-friendly form used in debug snapshots. */
    public Object toJson() {
        return switch (kind) {
            case NUMBER -> {
                if (number == Math.rint(number) && !Double.isInfinite(number)) {
                    yield (long) number;
                }
                yield number;
            }
            case BOOLEAN -> bool;
            case FUNCTION, NIL -> toDisplay();
        };
    }
}
