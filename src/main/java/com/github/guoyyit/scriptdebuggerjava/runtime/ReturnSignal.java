package com.github.guoyyit.scriptdebuggerjava.runtime;

/** Internal control-flow signal carrying a function return value. */
public class ReturnSignal extends RuntimeException {
    private final Value value;

    public ReturnSignal(Value value) {
        super(null, null, false, false);
        this.value = value;
    }

    public Value value() {
        return value;
    }
}
