package com.github.guoyyit.scriptdebuggerjava.runtime;

/** A runtime failure in the script (undefined variable, type error, ...). */
public class ScriptRuntimeException extends RuntimeException {
    private final int line;

    public ScriptRuntimeException(String message, int line) {
        super(message);
        this.line = line;
    }

    public int line() {
        return line;
    }
}
