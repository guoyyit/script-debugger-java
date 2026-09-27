package com.github.guoyyit.scriptdebuggerjava.api;

import com.github.guoyyit.scriptdebuggerjava.api.dto.BreakpointDto;
import com.github.guoyyit.scriptdebuggerjava.api.dto.CreateSessionRequest;
import com.github.guoyyit.scriptdebuggerjava.api.dto.FrameDto;
import com.github.guoyyit.scriptdebuggerjava.api.dto.PauseDto;
import com.github.guoyyit.scriptdebuggerjava.api.dto.SessionStatusResponse;
import com.github.guoyyit.scriptdebuggerjava.api.dto.SetBreakpointsRequest;
import com.github.guoyyit.scriptdebuggerjava.debug.Breakpoint;
import com.github.guoyyit.scriptdebuggerjava.debug.DebugSession;
import com.github.guoyyit.scriptdebuggerjava.debug.PauseSnapshot;
import com.github.guoyyit.scriptdebuggerjava.debug.SessionManager;
import com.github.guoyyit.scriptdebuggerjava.lang.FrameSnapshot;
import com.github.guoyyit.scriptdebuggerjava.lang.Interpreter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API for the local script debugger.
 */
@RestController
@RequestMapping("/api/debug/sessions")
public class DebugController {

    private final SessionManager sessions;

    public DebugController(SessionManager sessions) {
        this.sessions = sessions;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionStatusResponse create(@RequestBody CreateSessionRequest request) {
        if (request.script() == null || request.script().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "script must not be empty");
        }
        DebugSession session = sessions.create(
                request.script(),
                request.timeoutMs() != null ? request.timeoutMs() : 0,
                request.maxSteps() != null ? request.maxSteps() : 0);
        if (request.breakpoints() != null) {
            applyBreakpoints(session, request.breakpoints());
        }
        return status(session);
    }

    @PostMapping("/{id}/breakpoints")
    public SessionStatusResponse setBreakpoints(@PathVariable String id,
                                                @RequestBody SetBreakpointsRequest request) {
        DebugSession session = require(id);
        applyBreakpoints(session, request.breakpoints() != null ? request.breakpoints() : List.of());
        return status(session);
    }

    @PostMapping("/{id}/start")
    public SessionStatusResponse start(@PathVariable String id) {
        DebugSession session = require(id);
        try {
            session.start();
        } catch (IllegalStateException e) {
            throw conflict(e.getMessage());
        }
        return status(session);
    }

    @PostMapping("/{id}/resume")
    public SessionStatusResponse resume(@PathVariable String id) {
        DebugSession session = require(id);
        runCommand(session, session::resume);
        return status(session);
    }

    @PostMapping("/{id}/pause")
    public SessionStatusResponse pause(@PathVariable String id) {
        DebugSession session = require(id);
        session.pause();
        return status(session);
    }

    @PostMapping("/{id}/step/into")
    public SessionStatusResponse stepInto(@PathVariable String id) {
        DebugSession session = require(id);
        runCommand(session, session::stepInto);
        return status(session);
    }

    @PostMapping("/{id}/step/over")
    public SessionStatusResponse stepOver(@PathVariable String id) {
        DebugSession session = require(id);
        runCommand(session, session::stepOver);
        return status(session);
    }

    @PostMapping("/{id}/step/out")
    public SessionStatusResponse stepOut(@PathVariable String id) {
        DebugSession session = require(id);
        runCommand(session, session::stepOut);
        return status(session);
    }

    @PostMapping("/{id}/terminate")
    public SessionStatusResponse terminate(@PathVariable String id) {
        DebugSession session = require(id);
        session.terminate();
        return status(session);
    }

    @GetMapping("/{id}")
    public SessionStatusResponse get(@PathVariable String id) {
        return status(require(id));
    }

    @GetMapping("/{id}/stdout")
    public Map<String, Object> stdout(@PathVariable String id) {
        return Map.of("sessionId", id, "output", require(id).stdout());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void destroy(@PathVariable String id) {
        if (sessions.get(id) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such session: " + id);
        }
        sessions.destroy(id);
    }

    // ------------------------------------------------------------------

    private void applyBreakpoints(DebugSession session, List<BreakpointDto> dtos) {
        List<Breakpoint> breakpoints = dtos.stream()
                .map(d -> new Breakpoint(d.line(), d.condition()))
                .toList();
        try {
            session.setBreakpoints(breakpoints);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw conflict(e.getMessage());
        }
    }

    private void runCommand(DebugSession session, Runnable command) {
        try {
            command.run();
        } catch (IllegalStateException e) {
            throw conflict(e.getMessage());
        }
    }

    private DebugSession require(String id) {
        DebugSession session = sessions.get(id);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such session: " + id);
        }
        return session;
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private SessionStatusResponse status(DebugSession session) {
        PauseSnapshot pause = session.pauseInfo();
        PauseDto pauseDto = null;
        if (pause != null) {
            List<FrameDto> frames = pause.callStack().stream()
                    .map(this::toDto)
                    .toList();
            pauseDto = new PauseDto(pause.reason().name(), pause.line(),
                    pause.errorMessage(), frames);
        }
        return new SessionStatusResponse(
                session.id(),
                session.state().name(),
                session.terminationReason() != null ? session.terminationReason().name() : null,
                session.errorMessage(),
                pauseDto,
                session.stdout(),
                session.stepCount());
    }

    private FrameDto toDto(FrameSnapshot frame) {
        Map<String, Object> locals = new LinkedHashMap<>();
        frame.locals().forEach((name, value) -> locals.put(name, sanitize(value)));
        return new FrameDto(frame.functionName(), frame.line(), locals);
    }

    /** Keeps JSON-friendly values as-is; renders functions as strings. */
    private Object sanitize(Object value) {
        if (value == null || value instanceof Double || value instanceof Boolean
                || value instanceof String) {
            return value;
        }
        return Interpreter.stringify(value);
    }
}
