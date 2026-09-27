package com.github.guoyyit.scriptdebuggerjava.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DebugControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    private static final String SCRIPT = """
            let x = 1;
            fn double(n) {
                return n * 2;
            }
            let y = double(x);
            print(y);
            """;

    private String createSession(String script, String breakpointsJson) throws Exception {
        String body = """
                {"script": %s, "timeoutMs": 5000, "maxSteps": 10000, "breakpoints": %s}
                """.formatted(om.writeValueAsString(script), breakpointsJson);
        MvcResult result = mvc.perform(post("/api/debug/sessions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("CREATED"))
                .andReturn();
        return om.readTree(result.getResponse().getContentAsString()).get("sessionId").asText();
    }

    private JsonNode postAction(String id, String action) throws Exception {
        MvcResult result = mvc.perform(post("/api/debug/sessions/" + id + "/" + action))
                .andReturn();
        return om.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode awaitState(String id, String want) throws Exception {
        for (int i = 0; i < 500; i++) {
            MvcResult result = mvc.perform(get("/api/debug/sessions/" + id))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode node = om.readTree(result.getResponse().getContentAsString());
            if (node.get("state").asText().equals(want)) {
                return node;
            }
            Thread.sleep(10);
        }
        fail("timeout waiting for state " + want);
        return null;
    }

    @Test
    void fullDebugFlowOverHttp() throws Exception {
        String id = createSession(SCRIPT, "[{\"line\": 5}]");

        JsonNode started = postAction(id, "start");
        assertEquals("RUNNING", started.get("state").asText());

        JsonNode paused = awaitState(id, "PAUSED");
        assertEquals("BREAKPOINT", paused.get("pause").get("reason").asText());
        assertEquals(5, paused.get("pause").get("line").asInt());
        JsonNode frame = paused.get("pause").get("callStack").get(0);
        assertEquals("<script>", frame.get("function").asText());
        assertEquals(1, frame.get("locals").get("x").asInt());

        // Step into the function call.
        JsonNode stepped = postAction(id, "step/into");
        assertEquals("PAUSED", stepped.get("state").asText());
        assertEquals(3, stepped.get("pause").get("line").asInt());
        assertEquals("double", stepped.get("pause").get("callStack").get(0).get("function").asText());
        assertEquals(1, stepped.get("pause").get("callStack").get(0)
                .get("locals").get("n").asInt());

        // Step out back to top level.
        JsonNode out = postAction(id, "step/out");
        assertEquals("PAUSED", out.get("state").asText());
        assertEquals(6, out.get("pause").get("line").asInt());

        JsonNode finished = postAction(id, "resume");
        assertEquals("FINISHED", finished.get("state").asText());
        assertEquals("COMPLETED", finished.get("terminationReason").asText());
        assertEquals("2", finished.get("stdout").get(0).asText());

        mvc.perform(delete("/api/debug/sessions/" + id))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/debug/sessions/" + id))
                .andExpect(status().isNotFound());
    }

    @Test
    void conditionalBreakpointOverHttp() throws Exception {
        String script = "let i = 0;\nwhile (i < 5) {\n    i = i + 1;\n}\nprint(i);\n";
        String id = createSession(script, "[{\"line\": 3, \"condition\": \"i == 3\"}]");
        postAction(id, "start");
        JsonNode paused = awaitState(id, "PAUSED");
        assertEquals(3, paused.get("pause").get("line").asInt());
        assertEquals(3, paused.get("pause").get("callStack").get(0)
                .get("locals").get("i").asInt());
        postAction(id, "resume");
        JsonNode finished = awaitState(id, "FINISHED");
        assertEquals("5", finished.get("stdout").get(0).asText());
        mvc.perform(delete("/api/debug/sessions/" + id));
    }

    @Test
    void runtimeErrorOverHttp() throws Exception {
        String id = createSession("let x = 1;\nlet y = x / 0;\nprint(y);\n", "[]");
        postAction(id, "start");
        JsonNode paused = awaitState(id, "PAUSED_ON_ERROR");
        assertEquals("ERROR", paused.get("pause").get("reason").asText());
        assertEquals(2, paused.get("pause").get("line").asInt());
        assertTrue(paused.get("error").asText().contains("division by zero"));
        // Locals inspectable after error.
        assertEquals(1, paused.get("pause").get("callStack").get(0)
                .get("locals").get("x").asInt());

        JsonNode finished = postAction(id, "resume");
        assertEquals("FINISHED", finished.get("state").asText());
        assertEquals("ERROR", finished.get("terminationReason").asText());
        mvc.perform(delete("/api/debug/sessions/" + id));
    }

    @Test
    void timeoutOverHttp() throws Exception {
        String body = """
                {"script": "while (true) { let a = 1; }", "timeoutMs": 200, "maxSteps": -1}
                """;
        MvcResult result = mvc.perform(post("/api/debug/sessions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        String id = om.readTree(result.getResponse().getContentAsString())
                .get("sessionId").asText();
        postAction(id, "start");
        JsonNode terminated = awaitState(id, "TERMINATED");
        assertEquals("TIMEOUT", terminated.get("terminationReason").asText());
        mvc.perform(delete("/api/debug/sessions/" + id));
    }

    @Test
    void stdoutEndpoint() throws Exception {
        String id = createSession("print(\"hello\");\nprint(42);\n", "[]");
        postAction(id, "start");
        awaitState(id, "FINISHED");
        mvc.perform(get("/api/debug/sessions/" + id + "/stdout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.output[0]").value("hello"))
                .andExpect(jsonPath("$.output[1]").value("42"));
        mvc.perform(delete("/api/debug/sessions/" + id));
    }

    @Test
    void unknownSessionReturns404() throws Exception {
        mvc.perform(get("/api/debug/sessions/nope"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/debug/sessions/nope/resume"))
                .andExpect(status().isNotFound());
    }

    @Test
    void resumeWithoutPauseReturns409() throws Exception {
        String id = createSession("let x = 1;", "[]");
        mvc.perform(post("/api/debug/sessions/" + id + "/resume"))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/debug/sessions/" + id));
    }

    @Test
    void invalidBreakpointConditionReturns400() throws Exception {
        String body = """
                {"script": "let x = 1;", "breakpoints": [{"line": 1, "condition": "x > >"}]}
                """;
        mvc.perform(post("/api/debug/sessions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emptyScriptReturns400() throws Exception {
        mvc.perform(post("/api/debug/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"script\": \"\"}"))
                .andExpect(status().isBadRequest());
    }
}
