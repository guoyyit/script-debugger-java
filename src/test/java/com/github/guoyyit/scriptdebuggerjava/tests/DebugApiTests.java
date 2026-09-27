package com.github.guoyyit.scriptdebuggerjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end tests of the debug REST API. */
@SpringBootTest
@AutoConfigureMockMvc
class DebugApiTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    private JsonNode createSession(String body) throws Exception {
        MvcResult result = mvc.perform(post("/api/debug/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getSession(String id) throws Exception {
        MvcResult result = mvc.perform(get("/api/debug/sessions/{id}", id))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode awaitState(String id, Predicate<JsonNode> predicate) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        JsonNode snapshot = getSession(id);
        while (System.currentTimeMillis() < deadline) {
            snapshot = getSession(id);
            if (predicate.test(snapshot)) {
                return snapshot;
            }
            Thread.sleep(10);
        }
        return snapshot;
    }

    private void cleanup(String id) {
        try {
            mvc.perform(delete("/api/debug/sessions/{id}", id));
        } catch (Exception ignored) {
        }
    }

    @Test
    void fullDebugFlowOverHttp() throws Exception {
        String source = "let x = 1\nlet y = x + 1\nprint(y)\n";
        JsonNode created = createSession("""
                {"source": "%s", "breakpoints": [{"line": 2}]}
                """.formatted(source.replace("\n", "\\n")));
        String id = created.get("sessionId").asText();
        try {
            JsonNode paused = awaitState(id, s -> "PAUSED".equals(s.get("state").asText()));
            assertEquals("PAUSED", paused.get("state").asText());
            assertEquals("BREAKPOINT", paused.get("pauseReason").asText());
            assertEquals(2, paused.get("currentLine").asInt());
            JsonNode frame = paused.get("callStack").get(0);
            assertEquals("<script>", frame.get("functionName").asText());
            assertEquals(1, frame.get("locals").get("x").asInt());

            mvc.perform(post("/api/debug/sessions/{id}/commands/continue", id))
                    .andExpect(status().isOk());

            JsonNode ended = awaitState(id, s -> "ENDED".equals(s.get("state").asText()));
            assertEquals("COMPLETED", ended.get("terminationReason").asText());
            assertEquals("2", ended.get("output").get(0).asText());
        } finally {
            cleanup(id);
        }
    }

    @Test
    void conditionalBreakpointOverHttp() throws Exception {
        String source = "let i = 0\nwhile (i < 3) {\n  i = i + 1\n}\nprint(i)\n";
        // Pause at line 1 first so the breakpoint list can be replaced safely.
        JsonNode created = createSession("""
                {"source": "%s", "breakpoints": [{"line": 1}]}
                """.formatted(source.replace("\n", "\\n")));
        String id = created.get("sessionId").asText();
        try {
            JsonNode atLine1 = awaitState(id, s -> "PAUSED".equals(s.get("state").asText()));
            assertEquals(1, atLine1.get("currentLine").asInt());

            mvc.perform(put("/api/debug/sessions/{id}/breakpoints", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"breakpoints\": [{\"line\": 3, \"condition\": \"i == 2\"}]}"))
                    .andExpect(status().isOk());

            mvc.perform(post("/api/debug/sessions/{id}/commands/continue", id))
                    .andExpect(status().isOk());
            JsonNode paused = awaitState(id, s -> "PAUSED".equals(s.get("state").asText())
                    && s.get("currentLine").asInt() == 3);
            assertEquals(3, paused.get("currentLine").asInt());
            assertEquals(2, paused.get("callStack").get(0).get("locals").get("i").asInt());

            mvc.perform(post("/api/debug/sessions/{id}/commands/continue", id))
                    .andExpect(status().isOk());
            JsonNode ended = awaitState(id, s -> "ENDED".equals(s.get("state").asText()));
            assertEquals("COMPLETED", ended.get("terminationReason").asText());
            assertEquals("3", ended.get("output").get(0).asText());
        } finally {
            cleanup(id);
        }
    }

    @Test
    void deleteReleasesSession() throws Exception {
        JsonNode created = createSession("{\"source\": \"let a = 1\\n\"}");
        String id = created.get("sessionId").asText();

        mvc.perform(delete("/api/debug/sessions/{id}", id))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/debug/sessions/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void errorResponses() throws Exception {
        // Syntax error -> 400 with line
        mvc.perform(post("/api/debug/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\": \"let = 1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.line").value(1));

        // Unknown session -> 404
        mvc.perform(get("/api/debug/sessions/{id}", "does-not-exist"))
                .andExpect(status().isNotFound());

        // Empty source -> 400
        mvc.perform(post("/api/debug/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\": \"\"}"))
                .andExpect(status().isBadRequest());

        // Unknown command -> 400
        JsonNode created = createSession("{\"source\": \"let a = 1\\nprint(a)\\n\"}");
        String id = created.get("sessionId").asText();
        try {
            mvc.perform(post("/api/debug/sessions/{id}/commands/fly", id))
                    .andExpect(status().isBadRequest());

            JsonNode ended = awaitState(id, s -> "ENDED".equals(s.get("state").asText()));
            assertEquals("COMPLETED", ended.get("terminationReason").asText());

            // Command on an ended session -> 409
            mvc.perform(post("/api/debug/sessions/{id}/commands/continue", id))
                    .andExpect(status().isConflict());
        } finally {
            cleanup(id);
        }
    }

    @Test
    void listSessions() throws Exception {
        JsonNode created = createSession("{\"source\": \"let a = 1\\n\"}");
        String id = created.get("sessionId").asText();
        try {
            MvcResult result = mvc.perform(get("/api/debug/sessions"))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode list = objectMapper.readTree(result.getResponse().getContentAsString());
            assertTrue(list.isArray());
            boolean found = false;
            for (JsonNode node : list) {
                if (id.equals(node.get("sessionId").asText())) {
                    found = true;
                }
            }
            assertTrue(found, "created session not listed");
        } finally {
            cleanup(id);
        }
    }
}
