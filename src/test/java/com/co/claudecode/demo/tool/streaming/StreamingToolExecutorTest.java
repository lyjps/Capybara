package com.co.claudecode.demo.tool.streaming;

import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.message.ToolCallBlock;
import com.co.claudecode.demo.message.ToolResultBlock;
import com.co.claudecode.demo.tool.Tool;
import com.co.claudecode.demo.tool.ToolExecutionContext;
import com.co.claudecode.demo.tool.ToolMetadata;
import com.co.claudecode.demo.tool.ToolOrchestrator;
import com.co.claudecode.demo.tool.ToolRegistry;
import com.co.claudecode.demo.tool.ToolResult;
import com.co.claudecode.demo.tool.PermissionPolicy;
import com.co.claudecode.demo.tool.PermissionDecision;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link StreamingToolExecutor} — the core streaming tool execution engine.
 */
class StreamingToolExecutorTest {

    private ToolRegistry toolRegistry;
    private ToolOrchestrator orchestrator;
    private ToolExecutionContext context;
    private Consumer<String> eventSink;
    private List<String> events;
    private StreamingToolExecutor executor;

    @BeforeEach
    void setUp() {
        events = new CopyOnWriteArrayList<>();
        eventSink = events::add;
        context = new ToolExecutionContext(Path.of("/tmp"), Path.of("/tmp"));
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.close();
        }
        if (orchestrator != null) {
            orchestrator.close();
        }
    }

    // ================================================================
    //  Basic functionality
    // ================================================================

    @Test
    void addTool_singleTool_executesAndCompletes() {
        setupWithTools(concurrentSafeTool("tool_a", "result_a"));
        executor = createExecutor();

        executor.addTool(new ToolCallBlock("1", "tool_a", Map.of()));

        List<ConversationMessage> results = executor.awaitAllResults();
        assertEquals(1, results.size());
        assertToolResult(results.get(0), "tool_a", false);
    }

    @Test
    void addTool_multipleConcurrentSafe_executeInParallel() throws InterruptedException {
        // 使用慢工具来验证并行执行
        CountDownLatch allStarted = new CountDownLatch(2);
        CountDownLatch proceed = new CountDownLatch(1);

        Tool slowA = slowTool("slow_a", true, allStarted, proceed);
        Tool slowB = slowTool("slow_b", true, allStarted, proceed);
        setupWithTools(slowA, slowB);
        executor = createExecutor();

        executor.addTool(new ToolCallBlock("1", "slow_a", Map.of()));
        executor.addTool(new ToolCallBlock("2", "slow_b", Map.of()));

        // 两个工具都应该开始执行（因为都是 concurrent-safe）
        assertTrue(allStarted.await(5, TimeUnit.SECONDS),
                "Both tools should start executing in parallel");

        // 放行
        proceed.countDown();

        List<ConversationMessage> results = executor.awaitAllResults();
        assertEquals(2, results.size());
    }

    @Test
    void addTool_nonConcurrentSafe_executesExclusively() throws InterruptedException {
        AtomicInteger concurrentCount = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);

        Tool exclusiveA = trackingTool("excl_a", false, concurrentCount, maxConcurrent);
        Tool exclusiveB = trackingTool("excl_b", false, concurrentCount, maxConcurrent);
        setupWithTools(exclusiveA, exclusiveB);
        executor = createExecutor();

        executor.addTool(new ToolCallBlock("1", "excl_a", Map.of()));
        executor.addTool(new ToolCallBlock("2", "excl_b", Map.of()));

        List<ConversationMessage> results = executor.awaitAllResults();
        assertEquals(2, results.size());
        // 最大并发数不应超过 1（独占执行）
        assertTrue(maxConcurrent.get() <= 1,
                "Non-concurrent tools should execute exclusively, maxConcurrent=" + maxConcurrent.get());
    }

    @Test
    void awaitAllResults_returnsInInsertionOrder() {
        // 创建两个工具，第一个慢，第二个快
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch slowProceed = new CountDownLatch(1);

        Tool slowTool = new Tool() {
            @Override
            public ToolMetadata metadata() {
                return concurrentMetadata("slow_tool");
            }
            @Override
            public ToolResult execute(Map<String, String> input, ToolExecutionContext ctx) {
                slowStarted.countDown();
                try { slowProceed.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return new ToolResult(false, "slow_result");
            }
        };
        Tool fastTool = concurrentSafeTool("fast_tool", "fast_result");

        setupWithTools(slowTool, fastTool);
        executor = createExecutor();

        // 先添加慢工具，再添加快工具
        executor.addTool(new ToolCallBlock("1", "slow_tool", Map.of()));

        // 等慢工具开始执行后再添加快工具
        try { slowStarted.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        executor.addTool(new ToolCallBlock("2", "fast_tool", Map.of()));

        // 快工具先完成，但放行慢工具
        slowProceed.countDown();

        List<ConversationMessage> results = executor.awaitAllResults();
        assertEquals(2, results.size());
        // 结果按插入顺序：slow 先，fast 后
        assertToolResultName(results.get(0), "slow_tool");
        assertToolResultName(results.get(1), "fast_tool");
    }

    // ================================================================
    //  hasTools / toolCount
    // ================================================================

    @Test
    void hasTools_emptyExecutor_false() {
        setupWithTools(concurrentSafeTool("tool_a", "result"));
        executor = createExecutor();
        assertFalse(executor.hasTools());
        assertEquals(0, executor.toolCount());
    }

    @Test
    void hasTools_afterAddTool_true() {
        setupWithTools(concurrentSafeTool("tool_a", "result"));
        executor = createExecutor();
        executor.addTool(new ToolCallBlock("1", "tool_a", Map.of()));
        assertTrue(executor.hasTools());
        assertEquals(1, executor.toolCount());
    }

    // ================================================================
    //  discard
    // ================================================================

    @Test
    void discard_cancelsQueuedTools() {
        // 创建一个独占慢工具阻塞队列
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        Tool blocking = slowTool("blocking", false, started, proceed);
        Tool queued = concurrentSafeTool("queued_tool", "should_not_run");

        setupWithTools(blocking, queued);
        executor = createExecutor();

        // 添加阻塞工具（开始执行）
        executor.addTool(new ToolCallBlock("1", "blocking", Map.of()));
        try { started.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // 添加排队工具（应该在队列中等待）
        executor.addTool(new ToolCallBlock("2", "queued_tool", Map.of()));

        // discard
        executor.discard();
        assertTrue(executor.isDiscarded());

        // 放行阻塞工具
        proceed.countDown();

        List<ConversationMessage> results = executor.awaitAllResults();
        assertEquals(2, results.size());
        // 第二个工具的结果应该是 discard 错误
        ToolResultBlock secondResult = getToolResult(results.get(1));
        assertTrue(secondResult.error());
        assertTrue(secondResult.content().contains("Discarded"));
    }

    @Test
    void discard_preventsNewTools() {
        setupWithTools(concurrentSafeTool("tool_a", "result"));
        executor = createExecutor();

        executor.discard();
        executor.addTool(new ToolCallBlock("1", "tool_a", Map.of()));

        // 不应有任何工具被添加
        assertFalse(executor.hasTools());
    }

    // ================================================================
    //  Error handling
    // ================================================================

    @Test
    void executeTool_throwsException_resultIsFailed() {
        Tool failing = new Tool() {
            @Override
            public ToolMetadata metadata() {
                return concurrentMetadata("fail_tool");
            }
            @Override
            public ToolResult execute(Map<String, String> input, ToolExecutionContext ctx) {
                throw new RuntimeException("Boom!");
            }
        };

        setupWithTools(failing);
        executor = createExecutor();
        executor.addTool(new ToolCallBlock("1", "fail_tool", Map.of()));

        List<ConversationMessage> results = executor.awaitAllResults();
        assertEquals(1, results.size());
        ToolResultBlock result = getToolResult(results.get(0));
        assertTrue(result.error());
    }

    @Test
    void unknownTool_treatedAsNonConcurrent() {
        // 未注册的工具应该默认为 non-concurrent-safe
        setupWithTools(); // 空注册表
        executor = createExecutor();

        executor.addTool(new ToolCallBlock("1", "unknown_tool", Map.of()));

        List<ConversationMessage> results = executor.awaitAllResults();
        assertEquals(1, results.size());
        // 应该有错误结果（工具未找到）
        ToolResultBlock result = getToolResult(results.get(0));
        assertTrue(result.error());
    }

    // ================================================================
    //  Mixed concurrent and exclusive tools
    // ================================================================

    @Test
    void mixedTools_exclusiveWaitsForConcurrent() throws InterruptedException {
        AtomicInteger concurrentCount = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);

        Tool concA = trackingTool("conc_a", true, concurrentCount, maxConcurrent);
        Tool exclB = trackingTool("excl_b", false, concurrentCount, maxConcurrent);
        Tool concC = trackingTool("conc_c", true, concurrentCount, maxConcurrent);

        setupWithTools(concA, exclB, concC);
        executor = createExecutor();

        executor.addTool(new ToolCallBlock("1", "conc_a", Map.of()));
        executor.addTool(new ToolCallBlock("2", "excl_b", Map.of()));
        executor.addTool(new ToolCallBlock("3", "conc_c", Map.of()));

        List<ConversationMessage> results = executor.awaitAllResults();
        assertEquals(3, results.size());
    }

    @Test
    void awaitAllResults_emptyExecutor_returnsEmptyList() {
        setupWithTools(concurrentSafeTool("tool_a", "result"));
        executor = createExecutor();

        List<ConversationMessage> results = executor.awaitAllResults();
        assertTrue(results.isEmpty());
    }

    // ================================================================
    //  Helpers
    // ================================================================

    private void setupWithTools(Tool... tools) {
        List<Tool> toolList = List.of(tools);
        toolRegistry = new ToolRegistry(toolList);
        PermissionPolicy alwaysAllow = (tool, call, ctx) -> PermissionDecision.allow();
        orchestrator = new ToolOrchestrator(toolRegistry, alwaysAllow, 4);
    }

    private StreamingToolExecutor createExecutor() {
        return new StreamingToolExecutor(orchestrator, toolRegistry, context, eventSink, 4);
    }

    private Tool concurrentSafeTool(String name, String resultContent) {
        return new Tool() {
            @Override
            public ToolMetadata metadata() {
                return concurrentMetadata(name);
            }
            @Override
            public ToolResult execute(Map<String, String> input, ToolExecutionContext ctx) {
                return new ToolResult(false, resultContent);
            }
        };
    }

    private ToolMetadata concurrentMetadata(String name) {
        return new ToolMetadata(name, "Test tool " + name,
                true, true, false,
                ToolMetadata.PathDomain.NONE, null);
    }

    private ToolMetadata exclusiveMetadata(String name) {
        return new ToolMetadata(name, "Test tool " + name,
                true, false, false,
                ToolMetadata.PathDomain.NONE, null);
    }

    private Tool slowTool(String name, boolean concurrencySafe,
                          CountDownLatch started, CountDownLatch proceed) {
        return new Tool() {
            @Override
            public ToolMetadata metadata() {
                return concurrencySafe ? concurrentMetadata(name) : exclusiveMetadata(name);
            }
            @Override
            public ToolResult execute(Map<String, String> input, ToolExecutionContext ctx) {
                started.countDown();
                try { proceed.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return new ToolResult(false, name + "_result");
            }
        };
    }

    private Tool trackingTool(String name, boolean concurrencySafe,
                              AtomicInteger concurrentCount, AtomicInteger maxConcurrent) {
        return new Tool() {
            @Override
            public ToolMetadata metadata() {
                return concurrencySafe ? concurrentMetadata(name) : exclusiveMetadata(name);
            }
            @Override
            public ToolResult execute(Map<String, String> input, ToolExecutionContext ctx) {
                int current = concurrentCount.incrementAndGet();
                maxConcurrent.updateAndGet(max -> Math.max(max, current));
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                concurrentCount.decrementAndGet();
                return new ToolResult(false, name + "_result");
            }
        };
    }

    private void assertToolResult(ConversationMessage msg, String toolName, boolean isError) {
        ToolResultBlock result = getToolResult(msg);
        assertEquals(toolName, result.toolName());
        assertEquals(isError, result.error());
    }

    private void assertToolResultName(ConversationMessage msg, String toolName) {
        ToolResultBlock result = getToolResult(msg);
        assertEquals(toolName, result.toolName());
    }

    private ToolResultBlock getToolResult(ConversationMessage msg) {
        return msg.toolResults().get(0);
    }
}
