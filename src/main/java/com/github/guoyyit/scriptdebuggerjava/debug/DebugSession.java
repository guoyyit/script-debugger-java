package com.github.guoyyit.scriptdebuggerjava.debug;

import com.github.guoyyit.scriptdebuggerjava.lang.ExecutionListener;
import com.github.guoyyit.scriptdebuggerjava.lang.Expr;
import com.github.guoyyit.scriptdebuggerjava.lang.FrameSnapshot;
import com.github.guoyyit.scriptdebuggerjava.lang.Interpreter;
import com.github.guoyyit.scriptdebuggerjava.lang.Lexer;
import com.github.guoyyit.scriptdebuggerjava.lang.Parser;
import com.github.guoyyit.scriptdebuggerjava.lang.ScriptRuntimeException;
import com.github.guoyyit.scriptdebuggerjava.lang.Stmt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One script debugging session. Runs the script on a dedicated thread,
 * suspends on breakpoints / step commands and exposes consistent snapshots
 * of position, call stack and local variables while suspended.
 *
 * <p>Control commands ({@link #resume()}, {@link #stepInto()}, ...) block
 * until execution suspends again or the session ends, so the caller always
 * observes a consistent state after the call returns.</p>
 */
public class DebugSession implements ExecutionListener {

    /** Internal signal used to unwind the script thread on termination. */
    private static class SessionTerminatedException extends RuntimeException {
        final TerminationReason reason;

        SessionTerminatedException(TerminationReason reason) {
            super(null, null, false, false);
            this.reason = reason;
        }
    }

    private enum StepMode {
        NONE, INTO, OVER, OUT
    }

    /** A breakpoint with its condition pre-parsed. */
    private record PreparedBreakpoint(int line, String condition, Expr conditionExpr) {
    }

    private final String id;
    private final String script;
    private final long timeoutMs;
    private final long maxSteps;

    private final Object lock = new Object();
    private SessionState state = SessionState.CREATED;
    private PauseSnapshot pauseInfo;
    private TerminationReason terminationReason;
    private String errorMessage;
    private final List<String> stdout = new CopyOnWriteArrayList<>();

    private List<PreparedBreakpoint> breakpoints = List.of();
    private Thread thread;
    private Interpreter interpreter;

    private volatile boolean pauseRequested;
    private volatile boolean terminateRequested;
    private volatile TerminationReason requestedTermination = TerminationReason.USER_TERMINATED;
    private volatile StepMode stepMode = StepMode.NONE;
    private volatile int stepDepth;

    private long stepCount;
    private long deadlineNanos = Long.MAX_VALUE;
    private volatile long endedAtMillis;

    public DebugSession(String id, String script, long timeoutMs, long maxSteps) {
        this.id = id;
        this.script = script;
        this.timeoutMs = timeoutMs;
        this.maxSteps = maxSteps;
    }

    public String id() {
        return id;
    }

    /**
     * Replaces all breakpoints. Must be called before {@link #start()}.
     *
     * @throws IllegalArgumentException if a condition fails to parse
     */
    public void setBreakpoints(List<Breakpoint> breakpoints) {
        synchronized (lock) {
            if (state != SessionState.CREATED) {
                throw new IllegalStateException("breakpoints must be set before start");
            }
            List<PreparedBreakpoint> prepared = new ArrayList<>();
            for (Breakpoint bp : breakpoints) {
                Expr conditionExpr = null;
                if (bp.hasCondition()) {
                    try {
                        conditionExpr = new Parser().parseExpression(new Lexer().scan(bp.condition()));
                    } catch (Parser.ParseException e) {
                        throw new IllegalArgumentException(
                                "invalid breakpoint condition '" + bp.condition() + "': "
                                        + e.getMessage());
                    }
                }
                prepared.add(new PreparedBreakpoint(bp.line(), bp.condition(), conditionExpr));
            }
            this.breakpoints = List.copyOf(prepared);
        }
    }

    /** Starts the execution thread. */
    public void start() {
        List<Stmt> program;
        try {
            program = new Parser().parse(new Lexer().scan(script));
        } catch (Parser.ParseException e) {
            synchronized (lock) {
                state = SessionState.FINISHED;
                terminationReason = TerminationReason.ERROR;
                errorMessage = "parse error: " + e.getMessage();
            }
            return;
        }
        synchronized (lock) {
            if (state != SessionState.CREATED) {
                throw new IllegalStateException("session already started");
            }
            state = SessionState.RUNNING;
            if (timeoutMs > 0) {
                deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L;
            }
        }
        interpreter = new Interpreter(this, stdout::add);
        List<Stmt> statements = program;
        thread = new Thread(() -> run(statements), "debug-session-" + id);
        thread.setDaemon(true);
        thread.start();
    }

    private void run(List<Stmt> program) {
        try {
            interpreter.interpret(program);
            finish(TerminationReason.COMPLETED, null);
        } catch (SessionTerminatedException e) {
            finish(e.reason, null);
        } catch (ScriptRuntimeException e) {
            onRuntimeError(e);
        } catch (Throwable t) {
            finish(TerminationReason.ERROR, "internal error: " + t);
        }
    }

    private void onRuntimeError(ScriptRuntimeException e) {
        List<FrameSnapshot> stack = e.callStack() != null ? e.callStack() : List.of();
        synchronized (lock) {
            state = SessionState.PAUSED_ON_ERROR;
            pauseInfo = new PauseSnapshot(PauseReason.ERROR, e.line(), stack, e.getMessage());
            errorMessage = e.getMessage();
            lock.notifyAll();
            waitWhileLocked(SessionState.PAUSED_ON_ERROR);
        }
        if (terminateRequested) {
            finish(TerminationReason.USER_TERMINATED, null);
        } else {
            // User chose to resume after an error: the script cannot continue,
            // so the session ends with ERROR as the termination reason.
            finish(TerminationReason.ERROR, e.getMessage());
        }
    }

    private void finish(TerminationReason reason, String error) {
        synchronized (lock) {
            terminationReason = reason;
            if (error != null) {
                errorMessage = error;
            }
            state = switch (reason) {
                case COMPLETED, ERROR -> SessionState.FINISHED;
                case TIMEOUT, STEP_LIMIT, USER_TERMINATED -> SessionState.TERMINATED;
            };
            endedAtMillis = System.currentTimeMillis();
            pauseInfo = null;
            lock.notifyAll();
        }
    }

    // ------------------------------------------------------------------
    // ExecutionListener: runs on the script thread before every statement
    // ------------------------------------------------------------------

    @Override
    public void beforeStatement(int line, List<FrameSnapshot> callStack) {
        if (terminateRequested) {
            throw new SessionTerminatedException(requestedTermination);
        }
        stepCount++;
        if (maxSteps > 0 && stepCount > maxSteps) {
            throw new SessionTerminatedException(TerminationReason.STEP_LIMIT);
        }
        if (System.nanoTime() > deadlineNanos) {
            throw new SessionTerminatedException(TerminationReason.TIMEOUT);
        }

        PauseReason reason = null;
        String conditionError = null;

        if (pauseRequested) {
            pauseRequested = false;
            reason = PauseReason.PAUSE_REQUEST;
        } else if (stepMode != StepMode.NONE) {
            int depth = callStack.size();
            boolean hit = switch (stepMode) {
                case INTO -> true;
                case OVER -> depth <= stepDepth;
                case OUT -> depth < stepDepth;
                case NONE -> false;
            };
            if (hit) {
                stepMode = StepMode.NONE;
                reason = PauseReason.STEP;
            }
        }
        if (reason == null) {
            PreparedBreakpoint bp = breakpointAt(line);
            if (bp != null) {
                if (bp.conditionExpr() == null) {
                    reason = PauseReason.BREAKPOINT;
                } else {
                    try {
                        Object result = interpreter.evaluateInCurrentContext(bp.conditionExpr());
                        if (isTruthy(result)) {
                            reason = PauseReason.BREAKPOINT;
                        }
                    } catch (Exception e) {
                        // Condition evaluation failed: report a defined error
                        // and suspend, but never kill the session.
                        reason = PauseReason.CONDITION_ERROR;
                        conditionError = "breakpoint condition '" + bp.condition()
                                + "' failed: " + e.getMessage();
                    }
                }
            }
        }

        if (reason != null) {
            suspend(new PauseSnapshot(reason, line, callStack, conditionError));
        }
    }

    private void suspend(PauseSnapshot snapshot) {
        long suspendStart = System.nanoTime();
        synchronized (lock) {
            state = SessionState.PAUSED;
            pauseInfo = snapshot;
            lock.notifyAll();
            waitWhileLocked(SessionState.PAUSED);
        }
        // Suspended time does not count towards the execution timeout.
        deadlineNanos += System.nanoTime() - suspendStart;
        if (terminateRequested) {
            throw new SessionTerminatedException(requestedTermination);
        }
    }

    private void waitWhileLocked(SessionState expected) {
        while (state == expected && !terminateRequested) {
            try {
                lock.wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private PreparedBreakpoint breakpointAt(int line) {
        for (PreparedBreakpoint bp : breakpoints) {
            if (bp.line() == line) {
                return bp;
            }
        }
        return null;
    }

    private boolean isTruthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // state queries
    // ------------------------------------------------------------------

    public SessionState state() {
        synchronized (lock) {
            return state;
        }
    }

    /** Snapshot while PAUSED / PAUSED_ON_ERROR, otherwise null. */
    public PauseSnapshot pauseInfo() {
        synchronized (lock) {
            return pauseInfo;
        }
    }

    public TerminationReason terminationReason() {
        synchronized (lock) {
            return terminationReason;
        }
    }

    public String errorMessage() {
        synchronized (lock) {
            return errorMessage;
        }
    }

    public List<String> stdout() {
        return Collections.unmodifiableList(new ArrayList<>(stdout));
    }

    public long stepCount() {
        return stepCount;
    }

    // ------------------------------------------------------------------
    // control commands
    // ------------------------------------------------------------------

    /** Resumes from PAUSED / PAUSED_ON_ERROR; blocks until the next pause or termination. */
    public void resume() {
        command(StepMode.NONE);
    }

    /** Requests an asynchronous pause at the next statement. */
    public void pause() {
        synchronized (lock) {
            if (state == SessionState.RUNNING) {
                pauseRequested = true;
            }
        }
    }

    /** Steps into: suspends at the next statement at any call depth. */
    public void stepInto() {
        command(StepMode.INTO);
    }

    /** Steps over: suspends at the next statement at the same or shallower depth. */
    public void stepOver() {
        command(StepMode.OVER);
    }

    /** Steps out: suspends at the first statement in a shallower frame. */
    public void stepOut() {
        command(StepMode.OUT);
    }

    private void command(StepMode mode) {
        synchronized (lock) {
            if (state != SessionState.PAUSED && state != SessionState.PAUSED_ON_ERROR) {
                throw new IllegalStateException("session is not paused (state=" + state + ")");
            }
            if (state == SessionState.PAUSED_ON_ERROR) {
                // Resuming from an error ends the session; stepping makes no sense.
                state = SessionState.RUNNING;
                pauseInfo = null;
                lock.notifyAll();
                waitForStateChange();
                return;
            }
            stepMode = mode;
            stepDepth = pauseInfo.callStack().size();
            state = SessionState.RUNNING;
            pauseInfo = null;
            lock.notifyAll();
            waitForStateChange();
        }
    }

    /** Blocks while the session is running; returns on pause or termination. */
    private void waitForStateChange() {
        while (state == SessionState.RUNNING && !terminateRequested) {
            try {
                lock.wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Kills the session (user request). */
    public void terminate() {
        terminateWith(TerminationReason.USER_TERMINATED);
    }

    private void terminateWith(TerminationReason reason) {
        synchronized (lock) {
            if (state == SessionState.FINISHED || state == SessionState.TERMINATED) {
                return;
            }
            requestedTermination = reason;
            terminateRequested = true;
            lock.notifyAll();
        }
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    /** Releases all resources (thread, state). Idempotent. */
    public void close() {
        terminate();
        Thread t = thread;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** @return true once the execution thread has fully exited. */
    public boolean isThreadAlive() {
        Thread t = thread;
        return t != null && t.isAlive();
    }

    /**
     * @return epoch millis when the session ended, or 0 if still active. Used
     *         by the manager to reap stale sessions.
     */
    public long endedAtMillis() {
        return endedAtMillis;
    }
}
