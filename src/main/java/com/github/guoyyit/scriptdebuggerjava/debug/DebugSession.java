package com.github.guoyyit.scriptdebuggerjava.debug;

import com.github.guoyyit.scriptdebuggerjava.lang.Parser;
import com.github.guoyyit.scriptdebuggerjava.lang.ast.Stmt;
import com.github.guoyyit.scriptdebuggerjava.runtime.AbortSignal;
import com.github.guoyyit.scriptdebuggerjava.runtime.DebugHook;
import com.github.guoyyit.scriptdebuggerjava.runtime.Environment;
import com.github.guoyyit.scriptdebuggerjava.runtime.FrameView;
import com.github.guoyyit.scriptdebuggerjava.runtime.Interpreter;
import com.github.guoyyit.scriptdebuggerjava.runtime.ScriptRuntimeException;
import com.github.guoyyit.scriptdebuggerjava.runtime.Value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One debug session: parses the submitted script and runs it on a dedicated,
 * daemon thread. The interpreter thread blocks in {@link DebugHook} while a
 * pause is active; client threads drive it through the control methods.
 */
public final class DebugSession {

    /** Default execution-time budget (only time actually spent running counts). */
    public static final long DEFAULT_TIMEOUT_MS = 10_000L;
    /** Default maximum number of statements executed. */
    public static final long DEFAULT_MAX_STEPS = 100_000L;

    private enum StepMode { NONE, INTO, OVER, OUT }

    private final String id;
    private final Object lock = new Object();
    private final List<Stmt> program;
    private final Interpreter interpreter;
    private final List<String> output = new ArrayList<>();
    private final long maxSteps;
    private long deadline;

    private volatile List<Breakpoint> breakpoints = List.of();
    private Thread thread;

    // Guarded by lock.
    private SessionState state = SessionState.RUNNING;
    private PauseReason pauseReason;
    private TerminationReason terminationReason = TerminationReason.NONE;
    private Integer currentLine;
    private List<FrameSnapshot> callStack = List.of();
    private ErrorInfo errorInfo;
    private String conditionError;
    private long executedSteps;

    private boolean terminateRequested;
    private boolean pauseRequested;
    private boolean resumed;
    private StepMode stepMode = StepMode.NONE;
    private int stepDepth;

    public DebugSession(String id, SessionConfig config) {
        this.id = id;
        List<Breakpoint> bps = config.breakpoints() == null ? List.of() : List.copyOf(config.breakpoints());
        // Parse up front so syntax errors surface at session creation.
        this.program = new Parser().parse(config.source());
        this.breakpoints = bps;
        this.maxSteps = config.maxSteps() == null ? DEFAULT_MAX_STEPS : Math.max(0, config.maxSteps());
        long timeout = config.timeoutMs() == null ? DEFAULT_TIMEOUT_MS : Math.max(0, config.timeoutMs());
        this.interpreter = new Interpreter(this::onStatement, line -> {
            synchronized (lock) {
                output.add(line);
            }
        });
        this.deadline = System.currentTimeMillis() + timeout;
    }

    public String id() {
        return id;
    }

    /** Starts the execution thread. Idempotent. */
    public void start() {
        synchronized (lock) {
            if (thread != null) {
                return;
            }
            state = SessionState.RUNNING;
            thread = new Thread(this::run, "debug-session-" + id);
            thread.setDaemon(true);
            thread.start();
        }
    }

    /** Replaces all breakpoints. */
    public void setBreakpoints(List<Breakpoint> newBreakpoints) {
        breakpoints = newBreakpoints == null ? List.of() : List.copyOf(newBreakpoints);
    }

    public void continueRun() {
        synchronized (lock) {
            requirePaused();
            stepMode = StepMode.NONE;
            resumeLocked();
        }
    }

    /** Requests the running script to pause at the next statement. */
    public void pause() {
        synchronized (lock) {
            if (state == SessionState.RUNNING) {
                pauseRequested = true;
            }
        }
    }

    public void stepInto() {
        synchronized (lock) {
            requirePausedForStep();
            stepMode = StepMode.INTO;
            resumeLocked();
        }
    }

    public void stepOver() {
        synchronized (lock) {
            requirePausedForStep();
            stepMode = StepMode.OVER;
            stepDepth = interpreter.frames().size();
            resumeLocked();
        }
    }

    public void stepOut() {
        synchronized (lock) {
            requirePausedForStep();
            stepMode = StepMode.OUT;
            stepDepth = interpreter.frames().size();
            resumeLocked();
        }
    }

    /** Requests termination. The session ends with reason USER. Idempotent. */
    public void terminate() {
        Thread t;
        synchronized (lock) {
            if (state == SessionState.ENDED) {
                return;
            }
            terminateRequested = true;
            pauseRequested = false;
            resumed = true;
            lock.notifyAll();
            t = thread;
        }
        if (t != null) {
            t.interrupt();
        }
    }

    /** Stops the thread (if any) and waits for it to exit. Idempotent. */
    public void close() {
        terminate();
        Thread t;
        synchronized (lock) {
            t = thread;
        }
        if (t != null) {
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public SessionSnapshot snapshot() {
        synchronized (lock) {
            boolean running = state == SessionState.RUNNING;
            return new SessionSnapshot(
                    id,
                    state,
                    running ? null : pauseReason,
                    terminationReason,
                    running ? null : currentLine,
                    running ? List.of() : List.copyOf(callStack),
                    List.copyOf(output),
                    errorInfo,
                    conditionError,
                    executedSteps);
        }
    }

    // ---------- interpreter thread ----------

    private void run() {
        try {
            interpreter.interpret(program);
            synchronized (lock) {
                if (state != SessionState.ENDED) {
                    endLocked(TerminationReason.COMPLETED);
                }
            }
        } catch (AbortSignal signal) {
            synchronized (lock) {
                endLocked(switch (signal.reason()) {
                    case TIMEOUT -> TerminationReason.TIMEOUT;
                    case STEP_LIMIT -> TerminationReason.STEP_LIMIT;
                    case USER -> TerminationReason.USER;
                });
            }
        } catch (ScriptRuntimeException e) {
            handleException(e);
        } catch (RuntimeException e) {
            synchronized (lock) {
                errorInfo = new ErrorInfo("internal error: " + e.getClass().getSimpleName()
                        + ": " + e.getMessage(), currentLine);
                endLocked(TerminationReason.ERROR);
            }
        }
    }

    private void handleException(ScriptRuntimeException e) {
        ErrorInfo error = new ErrorInfo(e.getMessage(), e.line());
        List<FrameSnapshot> stack;
        synchronized (lock) {
            if (terminateRequested) {
                endLocked(TerminationReason.USER);
                return;
            }
            List<FrameView> frames = interpreter.errorFrames();
            stack = frames == null ? List.of() : snapshotFrames(frames);
            errorInfo = error;
            currentLine = e.line();
            callStack = stack;
            conditionError = null;
            pauseReason = PauseReason.EXCEPTION;
            state = SessionState.PAUSED;
            lock.notifyAll();
        }
        waitForResume();
        synchronized (lock) {
            if (terminateRequested) {
                endLocked(TerminationReason.USER);
            } else {
                // A failed statement cannot be restarted: inspection is
                // possible during the pause; continuing ends the run.
                // Restore the post-mortem view wiped by resumeLocked().
                errorInfo = error;
                currentLine = error.line();
                callStack = stack;
                pauseReason = PauseReason.EXCEPTION;
                endLocked(TerminationReason.ERROR);
            }
        }
    }

    private void onStatement(Stmt stmt, Environment env) {
        PauseReason reason;
        String condError = null;

        synchronized (lock) {
            executedSteps++;
            if (executedSteps > maxSteps) {
                throw new AbortSignal(AbortSignal.Reason.STEP_LIMIT);
            }
            if (terminateRequested) {
                throw new AbortSignal(AbortSignal.Reason.USER);
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AbortSignal(AbortSignal.Reason.TIMEOUT);
            }
        }

        // Explicit breakpoints take priority.
        Breakpoint hit = findBreakpoint(stmt.line());
        if (hit != null) {
            if (hit.condition() == null || hit.condition().isBlank()) {
                reason = PauseReason.BREAKPOINT;
            } else {
                try {
                    boolean satisfied = interpreter.evalExpression(hit.condition(), env).asBoolean(stmt.line());
                    reason = satisfied ? PauseReason.BREAKPOINT : null;
                } catch (RuntimeException conditionFailure) {
                    // Condition evaluation failure must not kill the session:
                    // pause and report the failure of the condition itself.
                    condError = conditionFailure.getMessage();
                    reason = PauseReason.BREAKPOINT;
                }
            }
        } else {
            reason = null;
        }

        if (reason == null) {
            synchronized (lock) {
                if (pauseRequested) {
                    pauseRequested = false;
                    reason = PauseReason.PAUSE;
                } else if (stepMode != StepMode.NONE) {
                    int depth = interpreter.frames().size();
                    boolean triggered = switch (stepMode) {
                        case INTO -> true;
                        case OVER -> depth <= stepDepth;
                        case OUT -> depth < stepDepth;
                        case NONE -> false;
                    };
                    if (triggered) {
                        reason = PauseReason.STEP;
                    }
                }
            }
        } else {
            synchronized (lock) {
                // Any explicit pause consumes a pending single-step request.
                stepMode = StepMode.NONE;
            }
        }

        if (reason == null) {
            return;
        }

        synchronized (lock) {
            if (terminateRequested) {
                throw new AbortSignal(AbortSignal.Reason.USER);
            }
            currentLine = stmt.line();
            callStack = snapshotFrames(interpreter.frames());
            conditionError = condError;
            pauseReason = reason;
            state = SessionState.PAUSED;
            lock.notifyAll();
        }

        long pauseStart = System.currentTimeMillis();
        waitForResume();
        long pausedFor = System.currentTimeMillis() - pauseStart;

        synchronized (lock) {
            deadline += pausedFor; // only running time counts toward the timeout
            if (terminateRequested) {
                throw new AbortSignal(AbortSignal.Reason.USER);
            }
            state = SessionState.RUNNING;
        }
    }

    private Breakpoint findBreakpoint(int line) {
        for (Breakpoint bp : breakpoints) {
            if (bp.line() == line) {
                return bp;
            }
        }
        return null;
    }

    private void waitForResume() {
        synchronized (lock) {
            while (!resumed && !terminateRequested) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            resumed = false;
        }
    }

    private void requirePaused() {
        if (state != SessionState.PAUSED) {
            throw new IllegalStateException("session is not paused (state=" + state + ")");
        }
    }

    private void requirePausedForStep() {
        requirePaused();
        if (pauseReason == PauseReason.EXCEPTION) {
            throw new IllegalStateException(
                    "single-step is not allowed after an exception; use continue or terminate");
        }
    }

    private void resumeLocked() {
        resumed = true;
        state = SessionState.RUNNING;
        currentLine = null;
        callStack = List.of();
        conditionError = null;
        pauseReason = null;
        lock.notifyAll();
    }

    private void endLocked(TerminationReason reason) {
        state = SessionState.ENDED;
        terminationReason = reason;
        if (reason == TerminationReason.COMPLETED) {
            currentLine = null;
            callStack = List.of();
            pauseReason = null;
        }
        lock.notifyAll();
    }

    private static List<FrameSnapshot> snapshotFrames(List<FrameView> frames) {
        List<FrameSnapshot> result = new ArrayList<>(frames.size());
        for (FrameView frame : frames) {
            Map<String, Object> locals = new LinkedHashMap<>();
            for (Map.Entry<String, Value> entry : frame.env().locals().entrySet()) {
                locals.put(entry.getKey(), entry.getValue().toJson());
            }
            result.add(new FrameSnapshot(frame.functionName(), frame.line(), locals));
        }
        return List.copyOf(result);
    }
}
