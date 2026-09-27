package com.github.guoyyit.scriptdebuggerjava.lang;

/** Raised when the script source cannot be lexed or parsed. */
public class ParseException extends RuntimeException {
    private final int line;

    public ParseException(String message, int line) {
        super(message);
        this.line = line;
    }

    /** 1-based source line where the error was detected. */
    public int line() {
        return line;
    }
}
