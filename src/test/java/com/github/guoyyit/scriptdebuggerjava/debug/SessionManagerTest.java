package com.github.guoyyit.scriptdebuggerjava.debug;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class SessionManagerTest {

    private final SessionManager manager = new SessionManager();

    @AfterEach
    void cleanup() {
        manager.shutdown();
    }

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
        fail("timeout waiting for " + want + ", actual " + s.state());
    }

    @Test
    void concurrentSessionsAreIndependent() throws Exception {
        int n = 8;
        List<DebugSession> sessions = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int value = i * 10;
            // Distinct script per session: pauses at line 2 with its own local.
            String script = "let v = " + value + ";\nlet w = v + 1;\nprint(w);\n";
            DebugSession session = manager.create(script, 10000, 10000);
            session.setBreakpoints(List.of(new Breakpoint(2)));
            sessions.add(session);
        }
        assertEquals(n, manager.size());

        // Start all sessions concurrently.
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch started = new CountDownLatch(n);
        for (DebugSession session : sessions) {
            pool.submit(() -> {
                session.start();
                started.countDown();
            });
        }
        assertTrue(started.await(5, TimeUnit.SECONDS));

        // Every session must pause at its own breakpoint with its own state.
        for (int i = 0; i < n; i++) {
            DebugSession session = sessions.get(i);
            awaitState(session, SessionState.PAUSED);
            assertEquals(2, session.pauseInfo().line());
            Object v = session.pauseInfo().callStack().get(0).locals().get("v");
            assertEquals(String.valueOf(i * 10), com.github.guoyyit.scriptdebuggerjava.lang
                    .Interpreter.stringify(v));
        }

        // Resume all concurrently; each prints its own value.
        CountDownLatch done = new CountDownLatch(n);
        for (DebugSession session : sessions) {
            pool.submit(() -> {
                session.resume();
                done.countDown();
            });
        }
        assertTrue(done.await(5, TimeUnit.SECONDS));
        pool.shutdown();

        for (int i = 0; i < n; i++) {
            DebugSession session = sessions.get(i);
            assertEquals(SessionState.FINISHED, session.state());
            assertEquals(TerminationReason.COMPLETED, session.terminationReason());
            assertEquals(List.of(String.valueOf(i * 10 + 1)), session.stdout());
        }
    }

    @Test
    void breakpointsDoNotLeakBetweenSessions() {
        DebugSession a = manager.create("print(1);\nprint(2);\n", 5000, 1000);
        DebugSession b = manager.create("print(10);\nprint(20);\n", 5000, 1000);
        a.setBreakpoints(List.of(new Breakpoint(2)));
        // b has no breakpoints: must run to completion without pausing.
        a.start();
        b.start();
        awaitState(a, SessionState.PAUSED);
        awaitState(b, SessionState.FINISHED);
        assertEquals(List.of("10", "20"), b.stdout());
        a.terminate();
    }

    @Test
    void destroyReleasesSessionResources() {
        DebugSession session = manager.create("while (true) { let a = 1; }", 60000, 0);
        session.start();
        awaitState(session, SessionState.RUNNING);
        String id = session.id();

        manager.destroy(id);
        assertNull(manager.get(id));
        assertEquals(0, manager.size());
        assertFalse(session.isThreadAlive());
        assertEquals(SessionState.TERMINATED, session.state());
    }

    @Test
    void finishedSessionThreadIsReaped() {
        DebugSession session = manager.create("print(1);", 5000, 1000);
        session.start();
        awaitState(session, SessionState.FINISHED);
        session.close();
        assertFalse(session.isThreadAlive());
    }

    @Test
    void shutdownTerminatesAllSessions() {
        DebugSession a = manager.create("while (true) { let a = 1; }", 60000, 0);
        DebugSession b = manager.create("print(1);", 60000, 0);
        a.start();
        b.start();
        awaitState(a, SessionState.RUNNING);
        manager.shutdown();
        assertEquals(0, manager.size());
        assertFalse(a.isThreadAlive());
        assertEquals(SessionState.TERMINATED, a.state());
        assertNotNull(b.state());
    }

    @Test
    void invalidScriptRejected() {
        try {
            manager.create("   ", 0, 0);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    void endedSessionsAreReapedAfterGracePeriod() {
        DebugSession ended = manager.create("print(1);", 5000, 1000);
        ended.start();
        awaitState(ended, SessionState.FINISHED);
        DebugSession active = manager.create("while (true) { let a = 1; }", 60000, 0);
        active.start();
        awaitState(active, SessionState.RUNNING);

        // Grace period of 0 reaps only ended sessions; active ones survive.
        int reaped = manager.reapEndedSessions(0);
        assertEquals(1, reaped);
        assertNull(manager.get(ended.id()));
        assertNotNull(manager.get(active.id()));
        assertFalse(ended.isThreadAlive());
        active.terminate();
    }
}
