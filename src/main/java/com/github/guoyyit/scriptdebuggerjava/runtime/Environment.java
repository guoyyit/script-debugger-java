package com.github.guoyyit.scriptdebuggerjava.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/** A lexical scope: variable bindings with a parent chain. */
public final class Environment {

    private final Environment parent;
    private final Map<String, Value> values = new LinkedHashMap<>();

    public Environment(Environment parent) {
        this.parent = parent;
    }

    /** Defines (or redefines) a variable in this scope. */
    public void define(String name, Value value) {
        values.put(name, value);
    }

    /** Assigns to an existing variable anywhere up the scope chain. */
    public void assign(String name, Value value, int line) {
        Environment env = this;
        while (env != null) {
            if (env.values.containsKey(name)) {
                env.values.put(name, value);
                return;
            }
            env = env.parent;
        }
        throw new ScriptRuntimeException("assignment to undefined variable '" + name + "'", line);
    }

    /** Looks a variable up, or fails for undefined references. */
    public Value get(String name, int line) {
        Environment env = this;
        while (env != null) {
            Value value = env.values.get(name);
            if (value != null) {
                return value;
            }
            env = env.parent;
        }
        throw new ScriptRuntimeException("undefined variable '" + name + "'", line);
    }

    /** Variables defined directly in this scope (not parents), as a stable copy. */
    public Map<String, Value> locals() {
        return new LinkedHashMap<>(values);
    }

    public Environment parent() {
        return parent;
    }
}
