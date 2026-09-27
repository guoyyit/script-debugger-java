package com.github.guoyyit.scriptdebuggerjava.debug;

import com.github.guoyyit.scriptdebuggerjava.lang.FrameSnapshot;
import com.github.guoyyit.scriptdebuggerjava.lang.Interpreter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class DebugSessionTest {

    private static final String SCRIPT = """
            let x = 1;
            let y = 2;
            fn add(a, b) {
                let s = a + b;
                return s;
            }
            let z = add(x, y);
            print(z);
            """;

    private static void awaitState(DebugSession s, SessionState want) {
        for (int i = 0; i < 500; i++) {
            if (s.state() == want) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted");
            }
        }
        fail("timeout waiting for state " + want + ", actual " + s.state());
    }

    private static String str(Object value) {
        return Interpreter.stringify(value);
    }

    @Test
    void lineBreakpointHitWithConsistentSnapshot() {
        DebugSession session = new DebugSession("t1", SCRIPT, 5000, 10000);
        session.setBreakpoints(List.of(new Breakpoint(7)));
        session.start();
        awaitState(session, SessionState.PAUSED);

        PauseSnapshot info = session.pauseInfo();
        assertEquals(PauseReason.BREAKPOINT, info.reason());
        assertEquals(7, info.line());
        assertEquals(1, info.callStack().size());
        FrameSnapshot frame = info.callStack().get(0);
        assertEquals("<script>", frame.functionName());
        Map<String, Object> locals = frame.locals();
        // x and y assigned, z not yet (breakpoint fires before the statement).
        assertEquals("1", str(locals.get("x")));
        assertEquals("2", str(locals.get("y")));
        assertFalse(locals.containsKey("z"));

        session.resume();
        awaitState(session, SessionState.FINISHED);
        assertEquals(TerminationReason.COMPLETED, session.terminationReason());
        assertEquals(List.of("3"), session.stdout());
        session.close();
        assertFalse(session.isThreadAlive());
    }

    @Test
    void conditionalBreakpointOnlyStopsWhenTrue() {
        String script = """
                let i = 0;
                let sum = 0;
                while (i < 10) {
                    sum = sum + i;
                    i = i + 1;
                }
                print(sum);
                """;
        DebugSession session = new DebugSession("t2", script, 5000, 10000);
        session.setBreakpoints(List.of(new Breakpoint(5, "i == 5")));
        session.start();
        awaitState(session, SessionState.PAUSED);

        PauseSnapshot info = session.pauseInfo();
        assertEquals(PauseReason.BREAKPOINT, info.reason());
        assertEquals(5, info.line());
        assertEquals("5", str(info.callStack().get(0).locals().get("i")));

        session.resume();
        awaitState(session, SessionState.FINISHED);
        assertEquals(List.of("45"), session.stdout());
        session.close();
    }

    @Test
    void failingConditionSuspendsWithErrorButSessionSurvives() {
        String script = """
                let a = 1;
                let b = 2;
                print(a + b);
                """;
        DebugSession session = new DebugSession("t3", script, 5000, 10000);
        session.setBreakpoints(List.of(new Breakpoint(2, "undefined_var > 1")));
        session.start();
        awaitState(session, SessionState.PAUSED);

        PauseSnapshot info = session.pauseInfo();
        assertEquals(PauseReason.CONDITION_ERROR, info.reason());
        assertEquals(2, info.line());
        assertNotNull(info.errorMessage());
        assertTrue(info.errorMessage().contains("undefined_var"));

        // Session is still usable: resume completes the script.
        session.resume();
        awaitState(session, SessionState.FINISHED);
        assertEquals(TerminationReason.COMPLETED, session.terminationReason());
        assertEquals(List.of("3"), session.stdout());
        session.close();
    }

    @Test
    void stepIntoEntersFunction() {
        DebugSession session = new DebugSession("t4", SCRIPT, 5000, 10000);
        session.setBreakpoints(List.of(new Breakpoint(7)));
        session.start();
        awaitState(session, SessionState.PAUSED);
        assertEquals(7, session.pauseInfo().line());

        session.stepInto();
        assertEquals(SessionState.PAUSED, session.state());
        PauseSnapshot info = session.pauseInfo();
        assertEquals(PauseReason.STEP, info.reason());
        assertEquals(4, info.line());
        assertEquals(2, info.callStack().size());
        assertEquals("add", info.callStack().get(0).functionName());
        assertEquals("<script>", info.callStack().get(1).functionName());
        // Function frame has its own locals.
        Map<String, Object> fnLocals = info.callStack().get(0).locals();
        assertEquals("1", str(fnLocals.get("a")));
        assertEquals("2", str(fnLocals.get("b")));

        session.terminate();
        session.close();
    }

    @Test
    void stepOverSkipsFunctionCall() {
        DebugSession session = new DebugSession("t5", SCRIPT, 5000, 10000);
        session.setBreakpoints(List.of(new Breakpoint(7)));
        session.start();
        awaitState(session, SessionState.PAUSED);

        session.stepOver();
        assertEquals(SessionState.PAUSED, session.state());
        PauseSnapshot info = session.pauseInfo();
        assertEquals(8, info.line());
        assertEquals(1, info.callStack().size());
        // z is assigned now.
        assertEquals("3", str(info.callStack().get(0).locals().get("z")));

        session.terminate();
        session.close();
    }

    @Test
    void stepOutReturnsToCaller() {
        DebugSession session = new DebugSession("t6", SCRIPT, 5000, 10000);
        session.setBreakpoints(List.of(new Breakpoint(4)));
        session.start();
        awaitState(session, SessionState.PAUSED);
        assertEquals(2, session.pauseInfo().callStack().size());

        session.stepOut();
        assertEquals(SessionState.PAUSED, session.state());
        PauseSnapshot info = session.pauseInfo();
        assertEquals(1, info.callStack().size());
        assertEquals(8, info.line());

        session.terminate();
        session.close();
    }

    @Test
    void runtimeErrorSuspendsForInspectionThenTerminates() {
        String script = """
                let a = 10;
                fn boom() {
                    let inner = 42;
                    return inner / 0;
                }
                let r = boom();
                print(r);
                """;
        DebugSession session = new DebugSession("t7", script, 5000, 10000);
        session.start();
        awaitState(session, SessionState.PAUSED_ON_ERROR);

        PauseSnapshot info = session.pauseInfo();
        assertEquals(PauseReason.ERROR, info.reason());
        assertEquals(4, info.line());
        assertTrue(info.errorMessage().contains("division by zero"));
        // Call stack and locals inspectable after the error.
        assertEquals(2, info.callStack().size());
        assertEquals("boom", info.callStack().get(0).functionName());
        assertEquals("42", str(info.callStack().get(0).locals().get("inner")));

        // User chooses to continue: session ends with ERROR reason.
        session.resume();
        awaitState(session, SessionState.FINISHED);
        assertEquals(TerminationReason.ERROR, session.terminationReason());
        assertTrue(session.errorMessage().contains("division by zero"));
        session.close();
    }

    @Test
    void terminateFromErrorState() {
        DebugSession session = new DebugSession("t8", "let x = 1 / 0;\n", 5000, 10000);
        session.start();
        awaitState(session, SessionState.PAUSED_ON_ERROR);
        session.terminate();
        awaitState(session, SessionState.TERMINATED);
        assertEquals(TerminationReason.USER_TERMINATED, session.terminationReason());
        session.close();
        assertFalse(session.isThreadAlive());
    }

    @Test
    void timeoutTerminatesSession() {
        DebugSession session = new DebugSession("t9", "while (true) { let a = 1; }", 200, 0);
        session.start();
        awaitState(session, SessionState.TERMINATED);
        assertEquals(TerminationReason.TIMEOUT, session.terminationReason());
        session.close();
        assertFalse(session.isThreadAlive());
    }

    @Test
    void stepLimitTerminatesSession() {
        DebugSession session = new DebugSession("t10", "while (true) { let a = 1; }", 0, 50);
        session.start();
        awaitState(session, SessionState.TERMINATED);
        assertEquals(TerminationReason.STEP_LIMIT, session.terminationReason());
        session.close();
        assertFalse(session.isThreadAlive());
    }

    @Test
    void userTerminateWhileRunning() {
        DebugSession session = new DebugSession("t11", "while (true) { let a = 1; }", 60000, 0);
        session.start();
        awaitState(session, SessionState.RUNNING);
        session.terminate();
        awaitState(session, SessionState.TERMINATED);
        assertEquals(TerminationReason.USER_TERMINATED, session.terminationReason());
        session.close();
        assertFalse(session.isThreadAlive());
    }

    @Test
    void asyncPauseWhileRunning() {
        DebugSession session = new DebugSession("t12",
                "let i = 0;\nwhile (i < 1000000) {\n    i = i + 1;\n}\nprint(i);",
                60000, 0);
        session.start();
        awaitState(session, SessionState.RUNNING);
        // Let the script get into the loop so `i` is already defined.
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        session.pause();
        awaitState(session, SessionState.PAUSED);
        assertEquals(PauseReason.PAUSE_REQUEST, session.pauseInfo().reason());
        assertNotNull(session.pauseInfo().callStack().get(0).locals().get("i"));

        session.resume();
        awaitState(session, SessionState.FINISHED);
        assertEquals(List.of("1000000"), session.stdout());
        session.close();
    }

    @Test
    void parseErrorEndsSessionWithError() {
        DebugSession session = new DebugSession("t13", "let x = ;", 5000, 1000);
        session.start();
        assertEquals(SessionState.FINISHED, session.state());
        assertEquals(TerminationReason.ERROR, session.terminationReason());
        assertTrue(session.errorMessage().contains("parse error"));
        session.close();
    }

    @Test
    void stdoutCapturedAcrossPause() {
        DebugSession session = new DebugSession("t14",
                "print(\"a\");\nprint(\"b\");\nprint(\"c\");", 5000, 1000);
        session.setBreakpoints(List.of(new Breakpoint(3)));
        session.start();
        awaitState(session, SessionState.PAUSED);
        assertEquals(List.of("a", "b"), session.stdout());
        session.resume();
        awaitState(session, SessionState.FINISHED);
        assertEquals(List.of("a", "b", "c"), session.stdout());
        session.close();
    }
}
