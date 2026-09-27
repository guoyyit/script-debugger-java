package com.github.guoyyit.scriptdebuggerjava.lang;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A lexical scope holding variable bindings, optionally chained to a parent scope.
 */
public class Environment {

    private final Environment parent;
    private final Map<String, Object> values = new LinkedHashMap<>();

    public Environment() {
        this.parent = null;
    }

    public Environment(Environment parent) {
        this.parent = parent;
    }

    public void define(String name, Object value) {
        values.put(name, value);
    }

    public Object get(String name, int line) {
        Environment env = this;
        while (env != null) {
            if (env.values.containsKey(name)) {
                return env.values.get(name);
            }
            env = env.parent;
        }
        throw new ScriptRuntimeException("undefined variable '" + name + "'", line, null);
    }

    public void assign(String name, Object value, int line) {
        Environment env = this;
        while (env != null) {
            if (env.values.containsKey(name)) {
                env.values.put(name, value);
                return;
            }
            env = env.parent;
        }
        throw new ScriptRuntimeException("cannot assign to undefined variable '" + name + "'", line, null);
    }

    /**
     * @return the enclosing scope, or null for the global scope.
     */
    public Environment parent() {
        return parent;
    }

    /**
     * @return variables declared directly in this scope (not parents).
     */
    public Map<String, Object> locals() {
        return new HashMap<>(values);
    }
}
