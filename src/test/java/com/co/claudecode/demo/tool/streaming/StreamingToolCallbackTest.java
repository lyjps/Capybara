package com.co.claudecode.demo.tool.streaming;

import com.co.claudecode.demo.message.ToolCallBlock;
import com.co.claudecode.demo.model.llm.StreamCallback;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link StreamingToolCallback} — the extended stream callback interface.
 */
class StreamingToolCallbackTest {

    @Test
    void streamingToolCallback_extendsStreamCallback() {
        // StreamingToolCallback 是 StreamCallback 的子接口
        StreamingToolCallback stc = new StreamingToolCallback() {
            @Override
            public void onTextToken(String token) {}
            @Override
            public void onToolUseComplete(ToolCallBlock toolCall) {}
        };
        assertInstanceOf(StreamCallback.class, stc);
    }

    @Test
    void onToolUseComplete_receivesCorrectToolCallBlock() {
        List<ToolCallBlock> received = new ArrayList<>();
        StreamingToolCallback stc = new StreamingToolCallback() {
            @Override
            public void onTextToken(String token) {}
            @Override
            public void onToolUseComplete(ToolCallBlock toolCall) {
                received.add(toolCall);
            }
        };

        ToolCallBlock block = new ToolCallBlock("id_1", "read_file",
                Map.of("path", "/tmp/test.txt"));
        stc.onToolUseComplete(block);

        assertEquals(1, received.size());
        assertEquals("id_1", received.get(0).id());
        assertEquals("read_file", received.get(0).toolName());
        assertEquals("/tmp/test.txt", received.get(0).input().get("path"));
    }

    @Test
    void onTextToken_stillWorks() {
        List<String> tokens = new ArrayList<>();
        StreamingToolCallback stc = new StreamingToolCallback() {
            @Override
            public void onTextToken(String token) {
                tokens.add(token);
            }
            @Override
            public void onToolUseComplete(ToolCallBlock toolCall) {}
        };

        stc.onTextToken("Hello ");
        stc.onTextToken("world");

        assertEquals(2, tokens.size());
        assertEquals("Hello ", tokens.get(0));
        assertEquals("world", tokens.get(1));
    }

    @Test
    void instanceofCheck_plainStreamCallback_notStreamingToolCallback() {
        StreamCallback plain = token -> {};
        assertFalse(plain instanceof StreamingToolCallback);
    }

    @Test
    void instanceofCheck_streamingToolCallback_isStreamCallback() {
        StreamCallback stc = new StreamingToolCallback() {
            @Override
            public void onTextToken(String token) {}
            @Override
            public void onToolUseComplete(ToolCallBlock toolCall) {}
        };
        assertTrue(stc instanceof StreamCallback);
        assertTrue(stc instanceof StreamingToolCallback);
    }

    @Test
    void onToolUseComplete_multipleCallsInOrder() {
        List<String> toolNames = new ArrayList<>();
        StreamingToolCallback stc = new StreamingToolCallback() {
            @Override
            public void onTextToken(String token) {}
            @Override
            public void onToolUseComplete(ToolCallBlock toolCall) {
                toolNames.add(toolCall.toolName());
            }
        };

        stc.onToolUseComplete(new ToolCallBlock("1", "tool_a", Map.of()));
        stc.onToolUseComplete(new ToolCallBlock("2", "tool_b", Map.of()));
        stc.onToolUseComplete(new ToolCallBlock("3", "tool_c", Map.of()));

        assertEquals(List.of("tool_a", "tool_b", "tool_c"), toolNames);
    }

    @Test
    void onToolUseComplete_emptyInput() {
        List<ToolCallBlock> received = new ArrayList<>();
        StreamingToolCallback stc = new StreamingToolCallback() {
            @Override
            public void onTextToken(String token) {}
            @Override
            public void onToolUseComplete(ToolCallBlock toolCall) {
                received.add(toolCall);
            }
        };

        stc.onToolUseComplete(new ToolCallBlock("id", "my_tool", Map.of()));
        assertEquals(1, received.size());
        assertTrue(received.get(0).input().isEmpty());
    }
}
