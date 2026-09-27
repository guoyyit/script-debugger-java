package com.github.guoyyit.scriptdebuggerjava.lang;

import java.util.List;

/**
 * A value that can be invoked from script code (user function or native builtin).
 */
public interface ScriptCallable {
    int arity();

    Object call(Interpreter interpreter, List<Object> arguments, int callLine);
}
