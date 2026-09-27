package com.github.guoyyit.scriptdebuggerjava.tests;

import com.github.guoyyit.scriptdebuggerjava.debug.Breakpoint;
import com.github.guoyyit.scriptdebuggerjava.debug.DebugSession;
import com.github.guoyyit.scriptdebuggerjava.debug.FrameSnapshot;
import com.github.guoyyit.scriptdebuggerjava.debug.PauseReason;
import com.github.guoyyit.scriptdebuggerjava.debug.SessionConfig;
import com.github.guoyyit.scriptdebuggerjava.debug.SessionManager;
import com.github.guoyyit.scriptdebuggerjava.debug.SessionNotFoundException;
import com.github.guoyyit.scriptdebuggerjava.debug.SessionSnapshot;
import com.github.guoyyit.scriptdebuggerjava.debug.SessionState;
import com.github.guoyyit.scriptdebuggerjava.debug.TerminationReason;
import com.github.guoyyit.scriptdebuggerjava.lang.ParseException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Service-level tests of the debug session state machine. */
class DebugSessionTests {

    private final SessionManager manager = new SessionManager();
    private final List<String> created = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String id : created) {
            try {
                manager.remove(id);
            } catch (SessionNotFoundException ignored) {
            }
        }
        created.clear();
    }

    private DebugSession start(String source, List<Breakpoint> breakpoints) {
        return start(source, breakpoints, null, null);
    }

    private DebugSession start(String source, List<Breakpoint> breakpoints, Long timeoutMs, Long maxSteps) {
        DebugSession session = manager.create(new SessionConfig(source, timeoutMs, maxSteps, breakpoints));
        created.add(session.id());
        return session;
    }

    private static SessionSnapshot await(DebugSession session, Predicate<SessionSnapshot> predicate) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            SessionSnapshot snapshot = session.snapshot();
            if (predicate.test(snapshot)) {
                return snapshot;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return session.snapshot();
    }

    private static SessionSnapshot awaitPaused(DebugSession session) {
        SessionSnapshot snapshot = await(session, s -> s.state() == SessionState.PAUSED);
        assertEquals(SessionState.PAUSED, snapshot.state(), "expected PAUSED, got " + snapshot);
        return snapshot;
    }

    private static SessionSnapshotEnded awaitEnded(DebugSession session) {
        SessionSnapshot snapshot = await(session, s -> s.state() == SessionState.ENDED);
        assertEquals(SessionState.ENDED, snapshot.state(), "expected ENDED, got " + snapshot);
        return new SessionSnapshotEnded(snapshot);
    }

    private record SessionSnapshotEnded(SessionSnapshot snapshot) {
    }

    // ---------- breakpoints ----------

    @Test
    void lineBreakpointPausesWithConsistentLocals() {
        String source = """
                let x = 1
                let y = 2
                let z = x + y
                print(z)
                """;
        DebugSession session = start(source, List.of(new Breakpoint(3, null)));

        SessionSnapshot paused = awaitPaused(session);
        assertEquals(PauseReason.BREAKPOINT, paused.pauseReason());
        assertEquals(3, paused.currentLine());
        assertEquals(1, paused.callStack().size());
        FrameSnapshot frame = paused.callStack().get(0);
        assertEquals("<script>", frame.functionName());
        // Statement at line 3 has not executed yet: x,y visible, z not yet.
        assertEquals(1L, frame.locals().get("x"));
        assertEquals(2L, frame.locals().get("y"));
        assertFalse(frame.locals().containsKey("z"));

        session.continueRun();
        SessionSnapshot ended = awaitEnded(session).snapshot();
        assertEquals(TerminationReason.COMPLETED, ended.terminationReason());
        assertEquals(List.of("3"), ended.output());
    }

    @Test
    void conditionalBreakpointOnlyStopsWhenConditionTrue() {
        String source = """
                let sum = 0
                let i = 0
                while (i < 5) {
                  i = i + 1
                  sum = sum + i
                }
                print(sum)
                """;
        DebugSession session = start(source, List.of(new Breakpoint(5, "i == 3")));

        SessionSnapshot paused = awaitPaused(session);
        assertEquals(PauseReason.BREAKPOINT, paused.pauseReason());
        assertEquals(5, paused.currentLine());
        Map<String, Object> locals = paused.callStack().get(0).locals();
        assertEquals(3L, locals.get("i"));
        assertEquals(3L, locals.get("sum")); // 1 + 2 so far
        assertNull(paused.conditionError());

        session.continueRun();
        SessionSnapshot ended = awaitEnded(session).snapshot();
        assertEquals(TerminationReason.COMPLETED, ended.terminationReason());
        assertEquals(List.of("15"), ended.output());
    }

    @Test
    void failingBreakpointConditionReportsErrorWithoutKillingSession() {
        String source = """
                let i = 0
                while (i < 3) {
                  i = i + 1
                }
                print(i)
                """;
        DebugSession session = start(source, List.of(new Breakpoint(3, "nope > 2")));

        SessionSnapshot paused = awaitPaused(session);
        assertEquals(PauseReason.BREAKPOINT, paused.pauseReason());
        assertNotNull(paused.conditionError());
        assertTrue(paused.conditionError().contains("nope"), paused.conditionError());
        assertEquals(SessionState.PAUSED, paused.state());

        // Session is still usable: remove the broken breakpoint and continue.
        session.setBreakpoints(List.of());
        session.continueRun();
        SessionSnapshot ended = awaitEnded(session).snapshot();
        assertEquals(TerminationReason.COMPLETED, ended.terminationReason());
        assertEquals(List.of("3"), ended.output());
    }

    // ---------- stepping ----------

    private static final String STEP_SCRIPT = """
            fn inc(a) {
              return a + 1
            }
            let r = inc(10)
            print(r)
            """;

    @Test
    void stepIntoEntersFunctionAndStepOutReturns() {
        DebugSession session = start(STEP_SCRIPT, List.of(new Breakpoint(4, null)));
        SessionSnapshot at4 = awaitPaused(session);
        assertEquals(4, at4.currentLine());

        session.stepInto();
        SessionSnapshot inside = awaitPaused(session);
        assertEquals(PauseReason.STEP, inside.pauseReason());
        assertEquals(2, inside.currentLine());
        assertEquals(2, inside.callStack().size());
        assertEquals("<script>", inside.callStack().get(0).functionName());
        assertEquals("inc", inside.callStack().get(1).functionName());
        assertEquals(10L, inside.callStack().get(1).locals().get("a"));

        session.stepOut();
        SessionSnapshot back = awaitPaused(session);
        assertEquals(5, back.currentLine());
        assertEquals(1, back.callStack().size());
        assertEquals(11L, back.callStack().get(0).locals().get("r"));

        session.stepOver(); // no more statements: runs to completion
        SessionSnapshot ended = awaitEnded(session).snapshot();
        assertEquals(TerminationReason.COMPLETED, ended.terminationReason());
        assertEquals(List.of("11"), ended.output());
    }

    @Test
    void stepOverDoesNotEnterFunction() {
        DebugSession session = start(STEP_SCRIPT, List.of(new Breakpoint(4, null)));
        SessionSnapshot at4 = awaitPaused(session);
        assertEquals(4, at4.currentLine());

        session.stepOver();
        SessionSnapshot at5 = awaitPaused(session);
        assertEquals(PauseReason.STEP, at5.pauseReason());
        assertEquals(5, at5.currentLine());
        assertEquals(1, at5.callStack().size());
        assertEquals(11L, at5.callStack().get(0).locals().get("r"));

        session.continueRun();
        SessionSnapshot ended = awaitEnded(session).snapshot();
        assertEquals(TerminationReason.COMPLETED, ended.terminationReason());
        assertEquals(List.of("11"), ended.output());
    }

    @Test
    void pauseCommandStopsRunningScript() {
        String source = """
                let i = 0
                while (i < 1000000000) {
                  i = i + 1
                }
                """;
        DebugSession session = start(source, List.of(), 30000L, null);
        session.pause();
        SessionSnapshot paused = awaitPaused(session);
        assertEquals(PauseReason.PAUSE, paused.pauseReason());
        assertNotNull(paused.currentLine());
        session.terminate();
        SessionSnapshot ended = awaitEnded(session).snapshot();
        assertEquals(TerminationReason.USER, ended.terminationReason());
    }

    // ---------- exceptions ----------

    @Test
    void runtimeErrorPausesWithStackAndLocalsThenTerminate() {
        String source = """
                let a = 10
                let b = 0
                let c = a / b
                print(c)
                """;
        DebugSession session = start(source, List.of());

        SessionSnapshot paused = awaitPaused(session);
        assertEquals(PauseReason.EXCEPTION, paused.pauseReason());
        assertEquals(3, paused.currentLine());
        assertNotNull(paused.error());
        assertTrue(paused.error().message().contains("division by zero"), paused.error().message());
        assertEquals(3, paused.error().line());
        Map<String, Object> locals = paused.callStack().get(0).locals();
        assertEquals(10L, locals.get("a"));
        assertEquals(0L, locals.get("b"));

        session.terminate();
        SessionSnapshot ended = awaitEnded(session).snapshot();
        assertEquals(TerminationReason.USER, ended.terminationReason());
    }

    @Test
    void runtimeErrorInsideFunctionCapturesCallStack() {
        String source = """
                fn boom(x) {
                  return x / 0
                }
                let v = boom(1)
                """;
        DebugSession session = start(source, List.of());

        SessionSnapshot paused = awaitPaused(session);
        assertEquals(PauseReason.EXCEPTION, paused.pauseReason());
        assertEquals(2, paused.error().line());
        assertEquals(2, paused.callStack().size());
        assertEquals("<script>", paused.callStack().get(0).functionName());
        assertEquals(4, paused.callStack().get(0).line());
        assertEquals("boom", paused.callStack().get(1).functionName());
        assertEquals(1L, paused.callStack().get(1).locals().get("x"));

        // Continue after an exception ends the run with ERROR, keeping the
        // post-mortem stack available for inspection.
        session.continueRun();
        SessionSnapshot ended = awaitEnded(session).snapshot();
        assertEquals(TerminationReason.ERROR, ended.terminationReason());
        assertNotNull(ended.error());
        assertEquals(2, ended.callStack().size());
    }

    @Test
    void undefinedVariableRaisesScriptErrorWithLine() {
        String source = """
                let a = 1
                let b = missing + 1
                """;
        DebugSession session = start(source, List.of());
        SessionSnapshot paused = awaitPaused(session);
        assertEquals(PauseReason.EXCEPTION, paused.pauseReason());
        assertTrue(paused.error().message().contains("missing"), paused.error().message());
        assertEquals(2, paused.error().line());
        session.terminate();
        awaitEnded(session);
    }

    // ---------- limits ----------

    @Test
    void executionTimeoutTerminatesSession() {
        String source = """
                let i = 0
                while (true) {
                  i = i + 1
                }
                """;
        DebugSession session = start(source, List.of(), 300L, 100_000_000L);
        SessionSnapshot ended = awaitEnded(session).snapshot();
        assertEquals(TerminationReason.TIMEOUT, ended.terminationReason());
    }

    @Test
    void stepLimitTerminatesSession() {
        String source = """
                let i = 0
                while (true) {
                  i = i + 1
                }
                """;
        DebugSession session = start(source, List.of(), 30000L, 50L);
        SessionSnapshot ended = awaitEnded(session).snapshot();
        assertEquals(TerminationReason.STEP_LIMIT, ended.terminationReason());
        assertTrue(ended.executedSteps() <= 51, "steps=" + ended.executedSteps());
    }

    // ---------- concurrency & lifecycle ----------

    @Test
    void concurrentSessionsAreIsolated() {
        String sourceA = """
                let x = 1
                let y = 10
                print(x)
                """;
        String sourceB = """
                let x = 2
                let y = 20
                let z = 30
                print(x)
                """;
        DebugSession a = start(sourceA, List.of(new Breakpoint(2, null)));
        DebugSession b = start(sourceB, List.of(new Breakpoint(3, null)));

        SessionSnapshot pausedA = awaitPaused(a);
        SessionSnapshot pausedB = awaitPaused(b);
        assertEquals(2, pausedA.currentLine());
        assertEquals(3, pausedB.currentLine());
        assertEquals(1L, pausedA.callStack().get(0).locals().get("x"));
        assertEquals(2L, pausedB.callStack().get(0).locals().get("x"));
        assertFalse(pausedA.callStack().get(0).locals().containsKey("z"));
        assertTrue(pausedB.callStack().get(0).locals().containsKey("y"));

        a.continueRun();
        b.continueRun();
        SessionSnapshot endedA = awaitEnded(a).snapshot();
        SessionSnapshot endedB = awaitEnded(b).snapshot();
        assertEquals(List.of("1"), endedA.output());
        assertEquals(List.of("2"), endedB.output());
        assertEquals(TerminationReason.COMPLETED, endedA.terminationReason());
        assertEquals(TerminationReason.COMPLETED, endedB.terminationReason());
    }

    @Test
    void removeReleasesSessionAndItsThread() throws InterruptedException {
        String source = """
                let i = 0
                while (true) {
                  i = i + 1
                }
                """;
        DebugSession session = start(source, List.of());
        String threadName = "debug-session-" + session.id();
        manager.remove(session.id());
        created.remove(session.id());

        assertThrows(SessionNotFoundException.class, () -> manager.get(session.id()));
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline && threadAlive(threadName)) {
            Thread.sleep(10);
        }
        assertFalse(threadAlive(threadName), "session thread still alive");
    }

    private static boolean threadAlive(String name) {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().equals(name) && t.isAlive()) {
                return true;
            }
        }
        return false;
    }

    @Test
    void invalidScriptFailsAtCreation() {
        assertThrows(ParseException.class,
                () -> manager.create(new SessionConfig("let = 1", null, null, List.of())));
    }

    @Test
    void commandsRequirePausedState() {
        String source = """
                let i = 0
                while (i < 1000000000) {
                  i = i + 1
                }
                """;
        DebugSession session = start(source, List.of(), 30000L, null);
        // While running, stepping/continuing is rejected.
        assertThrows(IllegalStateException.class, session::stepInto);
        assertThrows(IllegalStateException.class, session::continueRun);
        session.terminate();
        awaitEnded(session);
        // After termination, commands are rejected as well.
        assertThrows(IllegalStateException.class, session::stepInto);
    }
}
