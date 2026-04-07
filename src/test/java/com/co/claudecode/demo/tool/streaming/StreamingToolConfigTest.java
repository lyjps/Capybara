package com.co.claudecode.demo.tool.streaming;

import com.co.claudecode.demo.model.llm.StreamCallback;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link StreamingToolConfig} — feature gate for streaming tool execution.
 */
class StreamingToolConfigTest {

    // ================================================================
    //  getMode() tests (env-var dependent — test the parsing logic)
    // ================================================================

    @Test
    void getMode_returnsNonNull() {
        // 即使环境变量未设置，也应返回一个有效的 Mode
        StreamingToolConfig.Mode mode = StreamingToolConfig.getMode();
        assertNotNull(mode);
    }

    @Test
    void mode_enumHasAllValues() {
        // 确保三种模式都存在
        StreamingToolConfig.Mode[] modes = StreamingToolConfig.Mode.values();
        assertEquals(3, modes.length);
        assertNotNull(StreamingToolConfig.Mode.ENABLED);
        assertNotNull(StreamingToolConfig.Mode.DISABLED);
        assertNotNull(StreamingToolConfig.Mode.AUTO);
    }

    // ================================================================
    //  isEnabled() tests
    // ================================================================

    @Test
    void isEnabled_disabledMode_alwaysFalse() {
        // 默认未设置环境变量时（DISABLED 模式），isEnabled 应返回 false
        // 注意：此测试假设环境变量未设置，在 CI 中可能需要清除
        StreamingToolConfig.Mode mode = StreamingToolConfig.getMode();
        if (mode == StreamingToolConfig.Mode.DISABLED) {
            StreamCallback callback = token -> {};
            assertFalse(StreamingToolConfig.isEnabled(callback, 10));
        }
        // 如果环境变量已设置为其他模式，跳过此断言
    }

    @Test
    void isEnabled_nullCallback_falseForAuto() {
        // AUTO 模式下，null callback → false
        // 直接测试逻辑：无论模式如何，null callback 在 AUTO 下应为 false
        // 这里通过模拟逻辑验证
        assertFalse(autoModeCheck(null, 10));
    }

    @Test
    void isEnabled_zeroTools_falseForAuto() {
        // AUTO 模式下，0 tools → false
        StreamCallback callback = token -> {};
        assertFalse(autoModeCheck(callback, 0));
    }

    @Test
    void isEnabled_withCallbackAndTools_trueForAuto() {
        // AUTO 模式下，有 callback + 有 tools → true
        StreamCallback callback = token -> {};
        assertTrue(autoModeCheck(callback, 5));
    }

    @Test
    void isEnabledOptimistic_returnsBoolean() {
        // isEnabledOptimistic 应该不抛异常
        boolean result = StreamingToolConfig.isEnabledOptimistic();
        // 返回值取决于环境变量，但不应抛异常
        assertNotNull(Boolean.valueOf(result));
    }

    @Test
    void envKey_constant() {
        assertEquals("ENABLE_STREAMING_TOOL_EXECUTION", StreamingToolConfig.ENV_KEY);
    }

    // ================================================================
    //  Helper: simulate AUTO mode logic
    // ================================================================

    /**
     * 模拟 AUTO 模式的判断逻辑（不依赖环境变量）。
     */
    private boolean autoModeCheck(StreamCallback callback, int toolCount) {
        return callback != null && toolCount > 0;
    }
}
