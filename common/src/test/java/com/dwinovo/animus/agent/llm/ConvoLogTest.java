package com.dwinovo.animus.agent.llm;

import com.dwinovo.animus.agent.provider.AssistantTurn;
import com.dwinovo.animus.agent.provider.LlmToolCall;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JSONL conversation log IS the companion's memory across sessions —
 * round-trip fidelity (including provider extras like reasoning_content),
 * crash healing, and the compaction boundary are all load-bearing.
 */
class ConvoLogTest {

    @TempDir
    Path dir;

    private ConvoLog log() {
        return ConvoLog.forEntity(dir, UUID.fromString("00000000-0000-0000-0000-000000000001"));
    }

    @Test
    void roundTripsAllThreeRoles() {
        ConvoLog log = log();
        JsonObject extras = new JsonObject();
        extras.addProperty("reasoning_content", "thinking…");
        log.append(new ConvoState.Msg.User("挖十个铁"));
        log.append(new ConvoState.Msg.Assistant(new AssistantTurn("好的",
                List.of(new LlmToolCall("call_1", "auto_mine", "{\"block_ids\":[\"iron_ore\"]}")),
                extras)));
        log.append(new ConvoState.Msg.Tool("call_1", "{\"success\":true}"));

        List<ConvoState.Msg> loaded = log.load(ConvoLog.DEFAULT_LOAD_LIMIT);
        assertEquals(3, loaded.size());
        assertEquals("挖十个铁", ((ConvoState.Msg.User) loaded.get(0)).content());

        ConvoState.Msg.Assistant a = assertInstanceOf(ConvoState.Msg.Assistant.class, loaded.get(1));
        assertEquals("好的", a.turn().content());
        assertEquals(1, a.turn().toolCalls().size());
        assertEquals("auto_mine", a.turn().toolCalls().get(0).name());
        assertEquals("thinking…", a.turn().extras().get("reasoning_content").getAsString());

        ConvoState.Msg.Tool t = assertInstanceOf(ConvoState.Msg.Tool.class, loaded.get(2));
        assertEquals("call_1", t.toolCallId());
    }

    @Test
    void tornTailLineIsSkippedNotFatal() throws IOException {
        ConvoLog log = log();
        log.append(new ConvoState.Msg.User("hello"));
        Files.writeString(log.file(), "{\"role\":\"user\",\"cont", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);   // hard-kill mid-write
        List<ConvoState.Msg> loaded = log.load(ConvoLog.DEFAULT_LOAD_LIMIT);
        assertEquals(1, loaded.size());
    }

    @Test
    void compactBoundaryRestartsTheReplay() {
        ConvoLog log = log();
        log.append(new ConvoState.Msg.User("old turn 1"));
        log.append(new ConvoState.Msg.User("old turn 2"));
        log.appendCompactSummary("<summary>之前都做完了</summary>");
        log.append(new ConvoState.Msg.User("new turn"));

        List<ConvoState.Msg> loaded = log.load(ConvoLog.DEFAULT_LOAD_LIMIT);
        assertEquals(2, loaded.size());
        assertEquals("<summary>之前都做完了</summary>",
                ((ConvoState.Msg.User) loaded.get(0)).content());
        assertEquals("new turn", ((ConvoState.Msg.User) loaded.get(1)).content());
    }

    @Test
    void tailTrimWalksBackToAUserBoundary() {
        ConvoLog log = log();
        // turn 1: user + 4 tool-result pairs; turn 2: user + answer
        log.append(new ConvoState.Msg.User("turn one"));
        for (int i = 0; i < 4; i++) {
            log.append(new ConvoState.Msg.Assistant(new AssistantTurn("",
                    List.of(new LlmToolCall("c" + i, "wait", "{}")), null)));
            log.append(new ConvoState.Msg.Tool("c" + i, "ok"));
        }
        log.append(new ConvoState.Msg.User("turn two"));
        log.append(new ConvoState.Msg.Assistant(new AssistantTurn("done", List.of(), null)));

        // Limit 4 slices mid-chain (inside turn one's tool exchanges); the
        // walk-back extends BACKWARDS past the limit until it reaches a user
        // boundary — here all the way to "turn one". Bigger-than-limit is the
        // documented trade: protocol validity beats the soft cap.
        List<ConvoState.Msg> loaded = log.load(4);
        assertInstanceOf(ConvoState.Msg.User.class, loaded.get(0),
                "slice must open on a user message");
        assertEquals("turn one", ((ConvoState.Msg.User) loaded.get(0)).content());
        assertEquals(11, loaded.size());

        // A limit landing exactly on a user message needs no extension.
        List<ConvoState.Msg> exact = log.load(2);
        assertEquals(2, exact.size());
        assertEquals("turn two", ((ConvoState.Msg.User) exact.get(0)).content());
    }

    @Test
    void unansweredToolCallIdsFlagsTheKilledTask() {
        ConvoLog log = log();
        log.append(new ConvoState.Msg.User("dig"));
        log.append(new ConvoState.Msg.Assistant(new AssistantTurn("",
                List.of(new LlmToolCall("answered", "wait", "{}"),
                        new LlmToolCall("orphan", "auto_mine", "{}")), null)));
        log.append(new ConvoState.Msg.Tool("answered", "ok"));

        List<String> unanswered = ConvoLog.unansweredToolCallIds(
                log.load(ConvoLog.DEFAULT_LOAD_LIMIT));
        assertEquals(List.of("orphan"), unanswered);
    }

    @Test
    void missingFileLoadsEmpty() {
        assertTrue(log().load(10).isEmpty());
    }
}
