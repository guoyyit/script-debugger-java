package com.github.guoyyit.scriptdebuggerjava.api;

import com.github.guoyyit.scriptdebuggerjava.api.dto.BreakpointDto;
import com.github.guoyyit.scriptdebuggerjava.api.dto.SetBreakpointsRequest;
import com.github.guoyyit.scriptdebuggerjava.api.dto.StartSessionRequest;
import com.github.guoyyit.scriptdebuggerjava.debug.Breakpoint;
import com.github.guoyyit.scriptdebuggerjava.debug.DebugSession;
import com.github.guoyyit.scriptdebuggerjava.debug.SessionConfig;
import com.github.guoyyit.scriptdebuggerjava.debug.SessionManager;
import com.github.guoyyit.scriptdebuggerjava.debug.SessionSnapshot;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** REST API of the local script debugger. */
@RestController
@RequestMapping("/api/debug/sessions")
public class DebugController {

    private final SessionManager sessions;

    public DebugController(SessionManager sessions) {
        this.sessions = sessions;
    }

    /** Submits a script and starts its execution in a new debug session. */
    @PostMapping
    public SessionSnapshot create(@RequestBody StartSessionRequest request) {
        SessionConfig config = new SessionConfig(
                request.source(),
                request.timeoutMs(),
                request.maxSteps(),
                toBreakpoints(request.breakpoints()));
        return sessions.create(config).snapshot();
    }

    @GetMapping
    public List<SessionSnapshot> list() {
        return sessions.list().stream().map(DebugSession::snapshot).toList();
    }

    @GetMapping("/{id}")
    public SessionSnapshot get(@PathVariable String id) {
        return sessions.get(id).snapshot();
    }

    /** Replaces the whole breakpoint list of a session. */
    @PutMapping("/{id}/breakpoints")
    public SessionSnapshot setBreakpoints(@PathVariable String id, @RequestBody SetBreakpointsRequest request) {
        DebugSession session = sessions.get(id);
        session.setBreakpoints(toBreakpoints(request.breakpoints()));
        return session.snapshot();
    }

    /** Sends a control command: continue, pause, step-into, step-over, step-out, terminate. */
    @PostMapping("/{id}/commands/{command}")
    public SessionSnapshot command(@PathVariable String id, @PathVariable String command) {
        DebugSession session = sessions.get(id);
        switch (command) {
            case "continue" -> session.continueRun();
            case "pause" -> session.pause();
            case "step-into" -> session.stepInto();
            case "step-over" -> session.stepOver();
            case "step-out" -> session.stepOut();
            case "terminate" -> session.terminate();
            default -> throw new IllegalArgumentException("unknown command: " + command);
        }
        return session.snapshot();
    }

    /** Terminates the session (if needed) and releases all its resources. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        sessions.remove(id);
        return ResponseEntity.noContent().build();
    }

    private static List<Breakpoint> toBreakpoints(List<BreakpointDto> dtos) {
        if (dtos == null) {
            return List.of();
        }
        return dtos.stream()
                .map(dto -> new Breakpoint(dto.line(), dto.condition()))
                .toList();
    }
}
