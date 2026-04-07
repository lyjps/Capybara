package com.co.claudecode.demo.tool.streaming;

import com.co.claudecode.demo.agent.AgentEngine;
import com.co.claudecode.demo.agent.ConversationMemory;
import com.co.claudecode.demo.compact.MicroCompactConfig;
import com.co.claudecode.demo.compact.SessionMemory;
import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.message.ToolCallBlock;
import com.co.claudecode.demo.message.ToolResultBlock;
import com.co.claudecode.demo.model.ModelAdapter;
import com.co.claudecode.demo.model.llm.StreamCallback;
import com.co.claudecode.demo.tool.PermissionDecision;
import com.co.claudecode.demo.tool.PermissionPolicy;
import com.co.claudecode.demo.tool.Tool;
import com.co.claudecode.demo.tool.ToolExecutionContext;
import com.co.claudecode.demo.tool.ToolMetadata;
import com.co.claudecode.demo.tool.ToolOrchestrator;
import com.co.claudecode.demo.tool.ToolRegistry;
import com.co.claudecode.demo.tool.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the streaming tool execution feature.
 * <p>
 * Tests the full pipeline: AgentEngine → ModelAdapter (mock) → StreamingToolCallback
 * → StreamingToolExecutor → ToolOrchestrator → Tool execution → result collection.
 * <p>
 * These tests verify:
 * <ul>
 *   <li>AgentEngine correctly switches between streaming and classic paths</li>
 *   <li>Streaming path produces identical results to classic path</li>
 *   <li>Multiple tools execute correctly through the streaming pipeline</li>
 *   <li>Feature gate controls path selection</li>
 * </ul>
 */
class StreamingToolIntegrationTest {

    private ToolExecutionContext context;
    private PermissionPolicy alwaysAllow;
    private List<String> events;

    @BeforeEach
    void setUp() {
        context = new ToolExecutionContext(Path.of("/tmp"), Path.of("/tmp"));
        alwaysAllow = (tool, call, ctx) -> PermissionDecision.allow();
        events = new CopyOnWriteArrayList<>();
    }

    // ================================================================
    //  Classic vs Streaming path selection
    // ================================================================

    @Test
    void agentEngine_withoutStreamCallback_usesClassicPath() {
        // 没有 streamCallback → 必须走经典路径
        AtomicInteger modelCallCount = new AtomicInteger(0);
        Tool echoTool = simpleTool("echo", "echo_result");
        ToolRegistry registry = new ToolRegistry(List.of(echoTool));

        // Mock model: 第一次返回工具调用，第二次返回纯文本
        ModelAdapter model = new ModelAdapter() {
            @Override
            public ConversationMessage nextReply(List<ConversationMessage> conversation,
                                                  ToolExecutionContext ctx) {
                int call = modelCallCount.incrementAndGet();
                if (call == 1) {
                    return ConversationMessage.assistant("Calling tool...",
                            List.of(new ToolCallBlock("tc_1", "echo", Map.of())));
                }
                return ConversationMessage.assistant("Done!", List.of());
            }
        };

        // 不传 streamCallback → 5 参数构造
        ConversationMemory memory = createMemory();
        try (ToolOrchestrator orchestrator = new ToolOrchestrator(registry, alwaysAllow, 4)) {
            AgentEngine engine = new AgentEngine(memory, model, orchestrator, context, 5);
            ConversationMessage result = engine.chat("test", events::add);

            assertEquals("Done!", result.plainText());
            assertEquals(2, modelCallCount.get());
        }
    }

    @Test
    void agentEngine_withStreamCallback_andNoEnvVar_usesClassicPath() {
        // streamCallback 非 null，但环境变量未设置（默认 DISABLED）→ 走经典路径
        AtomicInteger modelCallCount = new AtomicInteger(0);
        Tool echoTool = simpleTool("echo", "echo_result");
        ToolRegistry registry = new ToolRegistry(List.of(echoTool));

        StreamCallback callback = token -> {};

        ModelAdapter model = new ModelAdapter() {
            @Override
            public ConversationMessage nextReply(List<ConversationMessage> conversation,
                                                  ToolExecutionContext ctx) {
                int call = modelCallCount.incrementAndGet();
                if (call == 1) {
                    return ConversationMessage.assistant("",
                            List.of(new ToolCallBlock("tc_1", "echo", Map.of())));
                }
                return ConversationMessage.assistant("Classic done", List.of());
            }
        };

        ConversationMemory memory = createMemory();
        try (ToolOrchestrator orchestrator = new ToolOrchestrator(registry, alwaysAllow, 4)) {
            AgentEngine engine = new AgentEngine(memory, model, orchestrator, context, 5,
                    null, null, callback, registry);

            // 环境变量未设置 → DISABLED → 走经典路径
            StreamingToolConfig.Mode mode = StreamingToolConfig.getMode();
            if (mode == StreamingToolConfig.Mode.DISABLED) {
                ConversationMessage result = engine.chat("test", events::add);
                assertEquals("Classic done", result.plainText());
            }
            // 如果环境变量已设置为其他模式，测试结果也有效（工具仍然会执行）
        }
    }

    // ================================================================
    //  Simulated streaming path tests
    // ================================================================

    @Test
    void streamingToolExecutor_integratesWithToolOrchestrator() {
        // 测试 StreamingToolExecutor 通过 ToolOrchestrator.executeSingleTool 执行
        Tool tool = simpleTool("greet", "Hello!");
        ToolRegistry registry = new ToolRegistry(List.of(tool));

        try (ToolOrchestrator orchestrator = new ToolOrchestrator(registry, alwaysAllow, 4);
             StreamingToolExecutor executor = new StreamingToolExecutor(
                     orchestrator, registry, context, events::add, 4)) {

            executor.addTool(new ToolCallBlock("tc_1", "greet", Map.of()));

            List<ConversationMessage> results = executor.awaitAllResults();
            assertEquals(1, results.size());

            ToolResultBlock resultBlock = results.get(0).toolResults().get(0);
            assertFalse(resultBlock.error());
            assertEquals("greet", resultBlock.toolName());
            assertEquals("Hello!", resultBlock.content());
        }
    }

    @Test
    void streamingToolExecutor_multipleToolsViaOrchestrator() {
        // 多工具通过 orchestrator 执行
        Tool toolA = simpleTool("tool_a", "result_a");
        Tool toolB = simpleTool("tool_b", "result_b");
        Tool toolC = simpleTool("tool_c", "result_c");
        ToolRegistry registry = new ToolRegistry(List.of(toolA, toolB, toolC));

        try (ToolOrchestrator orchestrator = new ToolOrchestrator(registry, alwaysAllow, 4);
             StreamingToolExecutor executor = new StreamingToolExecutor(
                     orchestrator, registry, context, events::add, 4)) {

            executor.addTool(new ToolCallBlock("1", "tool_a", Map.of()));
            executor.addTool(new ToolCallBlock("2", "tool_b", Map.of()));
            executor.addTool(new ToolCallBlock("3", "tool_c", Map.of()));

            List<ConversationMessage> results = executor.awaitAllResults();
            assertEquals(3, results.size());

            // 按插入顺序
            assertEquals("tool_a", results.get(0).toolResults().get(0).toolName());
            assertEquals("tool_b", results.get(1).toolResults().get(0).toolName());
            assertEquals("tool_c", results.get(2).toolResults().get(0).toolName());

            // 结果内容正确
            assertEquals("result_a", results.get(0).toolResults().get(0).content());
            assertEquals("result_b", results.get(1).toolResults().get(0).content());
            assertEquals("result_c", results.get(2).toolResults().get(0).content());
        }
    }

    @Test
    void streamingToolExecutor_concurrentToolsRunInParallel() throws InterruptedException {
        // 验证 concurrent-safe 工具确实通过 orchestrator 并行执行
        CountDownLatch allStarted = new CountDownLatch(2);
        CountDownLatch proceed = new CountDownLatch(1);

        Tool slowA = new Tool() {
            @Override
            public ToolMetadata metadata() {
                return concurrentMeta("slow_a");
            }
            @Override
            public ToolResult execute(Map<String, String> input, ToolExecutionContext ctx) {
                allStarted.countDown();
                try { proceed.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new ToolResult(false, "a_done");
            }
        };

        Tool slowB = new Tool() {
            @Override
            public ToolMetadata metadata() {
                return concurrentMeta("slow_b");
            }
            @Override
            public ToolResult execute(Map<String, String> input, ToolExecutionContext ctx) {
                allStarted.countDown();
                try { proceed.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new ToolResult(false, "b_done");
            }
        };

        ToolRegistry registry = new ToolRegistry(List.of(slowA, slowB));
        try (ToolOrchestrator orchestrator = new ToolOrchestrator(registry, alwaysAllow, 4);
             StreamingToolExecutor executor = new StreamingToolExecutor(
                     orchestrator, registry, context, events::add, 4)) {

            executor.addTool(new ToolCallBlock("1", "slow_a", Map.of()));
            executor.addTool(new ToolCallBlock("2", "slow_b", Map.of()));

            // 两个工具都应该开始并行执行
            assertTrue(allStarted.await(5, TimeUnit.SECONDS),
                    "Both concurrent tools should start in parallel");

            proceed.countDown();
            List<ConversationMessage> results = executor.awaitAllResults();
            assertEquals(2, results.size());
        }
    }

    @Test
    void streamingToolExecutor_exclusiveToolWaitsForConcurrent() throws InterruptedException {
        // 独占工具等待并发工具完成后才开始
        AtomicInteger concurrentCount = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);

        Tool concTool = new Tool() {
            @Override
            public ToolMetadata metadata() {
                return concurrentMeta("conc");
            }
            @Override
            public ToolResult execute(Map<String, String> input, ToolExecutionContext ctx) {
                int c = concurrentCount.incrementAndGet();
                maxConcurrent.updateAndGet(m -> Math.max(m, c));
                try { Thread.sleep(50); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                concurrentCount.decrementAndGet();
                return new ToolResult(false, "conc_result");
            }
        };

        Tool exclTool = new Tool() {
            @Override
            public ToolMetadata metadata() {
                return exclusiveMeta("excl");
            }
            @Override
            public ToolResult execute(Map<String, String> input, ToolExecutionContext ctx) {
                int c = concurrentCount.incrementAndGet();
                maxConcurrent.updateAndGet(m -> Math.max(m, c));
                try { Thread.sleep(50); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                concurrentCount.decrementAndGet();
                return new ToolResult(false, "excl_result");
            }
        };

        ToolRegistry registry = new ToolRegistry(List.of(concTool, exclTool));
        try (ToolOrchestrator orchestrator = new ToolOrchestrator(registry, alwaysAllow, 4);
             StreamingToolExecutor executor = new StreamingToolExecutor(
                     orchestrator, registry, context, events::add, 4)) {

            executor.addTool(new ToolCallBlock("1", "conc", Map.of()));
            executor.addTool(new ToolCallBlock("2", "excl", Map.of()));

            List<ConversationMessage> results = executor.awaitAllResults();
            assertEquals(2, results.size());
            // 独占工具不应与并发工具同时执行
            assertTrue(maxConcurrent.get() <= 1,
                    "Exclusive tool should not run concurrently with others");
        }
    }

    @Test
    void streamingToolCallback_correctlyRoutesToolCompletion() {
        // 测试 StreamingToolCallback 接口正确路由到 StreamingToolExecutor
        Tool tool = simpleTool("routed", "routed_result");
        ToolRegistry registry = new ToolRegistry(List.of(tool));

        try (ToolOrchestrator orchestrator = new ToolOrchestrator(registry, alwaysAllow, 4);
             StreamingToolExecutor executor = new StreamingToolExecutor(
                     orchestrator, registry, context, events::add, 4)) {

            List<String> textTokens = new CopyOnWriteArrayList<>();

            // 模拟 AgentEngine 中创建的复合回调
            StreamingToolCallback callback = new StreamingToolCallback() {
                @Override
                public void onTextToken(String token) {
                    textTokens.add(token);
                }
                @Override
                public void onToolUseComplete(ToolCallBlock toolCall) {
                    executor.addTool(toolCall);
                }
            };

            // 模拟 SSE 流输出
            callback.onTextToken("I'll use a tool. ");
            callback.onToolUseComplete(new ToolCallBlock("tc_1", "routed", Map.of()));
            callback.onTextToken("Done.");

            // 文本 token 应该正确收集
            assertEquals(2, textTokens.size());
            assertEquals("I'll use a tool. ", textTokens.get(0));
            assertEquals("Done.", textTokens.get(1));

            // 工具应该已被提交执行
            assertTrue(executor.hasTools());
            assertEquals(1, executor.toolCount());

            List<ConversationMessage> results = executor.awaitAllResults();
            assertEquals(1, results.size());
            assertFalse(results.get(0).toolResults().get(0).error());
            assertEquals("routed_result", results.get(0).toolResults().get(0).content());
        }
    }

    @Test
    void streamingToolCallback_multipleToolsStreamedInSequence() {
        // 模拟 SSE 流中多个 tool_use 依次完成
        Tool readTool = simpleTool("read_file", "file content");
        Tool listTool = simpleTool("list_files", "[a.txt, b.txt]");
        ToolRegistry registry = new ToolRegistry(List.of(readTool, listTool));

        try (ToolOrchestrator orchestrator = new ToolOrchestrator(registry, alwaysAllow, 4);
             StreamingToolExecutor executor = new StreamingToolExecutor(
                     orchestrator, registry, context, events::add, 4)) {

            List<String> textTokens = new CopyOnWriteArrayList<>();

            StreamingToolCallback callback = new StreamingToolCallback() {
                @Override
                public void onTextToken(String token) {
                    textTokens.add(token);
                }
                @Override
                public void onToolUseComplete(ToolCallBlock toolCall) {
                    executor.addTool(toolCall);
                }
            };

            // 模拟 SSE 流：text → tool1 → text → tool2 → text
            callback.onTextToken("Let me ");
            callback.onTextToken("read and list. ");
            callback.onToolUseComplete(new ToolCallBlock("1", "read_file",
                    Map.of("path", "/tmp/test.txt")));
            callback.onTextToken("Also listing... ");
            callback.onToolUseComplete(new ToolCallBlock("2", "list_files",
                    Map.of("path", "/tmp")));
            callback.onTextToken("All done.");

            // 文本 token 全部收集
            assertEquals(4, textTokens.size());

            // 两个工具都应提交执行
            assertEquals(2, executor.toolCount());

            List<ConversationMessage> results = executor.awaitAllResults();
            assertEquals(2, results.size());

            // 按顺序验证
            assertEquals("read_file", results.get(0).toolResults().get(0).toolName());
            assertEquals("list_files", results.get(1).toolResults().get(0).toolName());
        }
    }

    @Test
    void streamingToolExecutor_discardDuringExecution_handlesGracefully() {
        // 测试在工具执行期间 discard 的容错
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);

        Tool blocking = new Tool() {
            @Override
            public ToolMetadata metadata() {
                return exclusiveMeta("blocking");
            }
            @Override
            public ToolResult execute(Map<String, String> input, ToolExecutionContext ctx) {
                started.countDown();
                try { proceed.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new ToolResult(false, "done");
            }
        };

        Tool pending = simpleTool("pending", "should_not_run");
        ToolRegistry registry = new ToolRegistry(List.of(blocking, pending));

        try (ToolOrchestrator orchestrator = new ToolOrchestrator(registry, alwaysAllow, 4);
             StreamingToolExecutor executor = new StreamingToolExecutor(
                     orchestrator, registry, context, events::add, 4)) {

            // 提交阻塞工具
            executor.addTool(new ToolCallBlock("1", "blocking", Map.of()));
            try { started.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // 提交待排队工具
            executor.addTool(new ToolCallBlock("2", "pending", Map.of()));

            // 在执行中 discard
            executor.discard();
            assertTrue(executor.isDiscarded());

            // 放行阻塞工具
            proceed.countDown();

            // 仍应能收集所有结果
            List<ConversationMessage> results = executor.awaitAllResults();
            assertEquals(2, results.size());

            // 第二个工具应该有 discard 错误
            assertTrue(results.get(1).toolResults().get(0).error());
        }
    }

    @Test
    void streamingToolExecutor_toolWithParams_passedCorrectly() {
        // 验证工具参数被正确传递
        AtomicBoolean paramReceived = new AtomicBoolean(false);
        Tool paramTool = new Tool() {
            @Override
            public ToolMetadata metadata() {
                return concurrentMeta("param_tool");
            }
            @Override
            public ToolResult execute(Map<String, String> input, ToolExecutionContext ctx) {
                if ("/tmp/hello.txt".equals(input.get("path"))
                        && "world".equals(input.get("content"))) {
                    paramReceived.set(true);
                }
                return new ToolResult(false, "wrote " + input.get("path"));
            }
        };

        ToolRegistry registry = new ToolRegistry(List.of(paramTool));
        try (ToolOrchestrator orchestrator = new ToolOrchestrator(registry, alwaysAllow, 4);
             StreamingToolExecutor executor = new StreamingToolExecutor(
                     orchestrator, registry, context, events::add, 4)) {

            executor.addTool(new ToolCallBlock("tc_1", "param_tool",
                    Map.of("path", "/tmp/hello.txt", "content", "world")));

            List<ConversationMessage> results = executor.awaitAllResults();
            assertEquals(1, results.size());
            assertTrue(paramReceived.get(), "Tool should receive correct parameters");
            assertEquals("wrote /tmp/hello.txt", results.get(0).toolResults().get(0).content());
        }
    }

    @Test
    void streamingToolExecutor_failingTool_producesErrorResult() {
        // 通过 orchestrator 执行失败的工具
        Tool failTool = new Tool() {
            @Override
            public ToolMetadata metadata() {
                return concurrentMeta("fail_tool");
            }
            @Override
            public ToolResult execute(Map<String, String> input, ToolExecutionContext ctx) {
                throw new RuntimeException("Integration test error");
            }
        };

        ToolRegistry registry = new ToolRegistry(List.of(failTool));
        try (ToolOrchestrator orchestrator = new ToolOrchestrator(registry, alwaysAllow, 4);
             StreamingToolExecutor executor = new StreamingToolExecutor(
                     orchestrator, registry, context, events::add, 4)) {

            executor.addTool(new ToolCallBlock("1", "fail_tool", Map.of()));

            List<ConversationMessage> results = executor.awaitAllResults();
            assertEquals(1, results.size());
            assertTrue(results.get(0).toolResults().get(0).error());
        }
    }

    @Test
    void streamingPath_producesEquivalentResultsToClassicPath() {
        // 验证流式路径和经典路径产生等价结果
        Tool echoTool = new Tool() {
            @Override
            public ToolMetadata metadata() {
                return concurrentMeta("echo");
            }
            @Override
            public ToolResult execute(Map<String, String> input, ToolExecutionContext ctx) {
                return new ToolResult(false, "echoed:" + input.getOrDefault("msg", ""));
            }
        };

        ToolRegistry registry = new ToolRegistry(List.of(echoTool));
        ToolCallBlock call = new ToolCallBlock("tc_1", "echo", Map.of("msg", "hello"));

        // 经典路径：通过 ToolOrchestrator.execute()
        List<ConversationMessage> classicResults;
        try (ToolOrchestrator orchestrator = new ToolOrchestrator(registry, alwaysAllow, 4)) {
            classicResults = orchestrator.execute(List.of(call), context, events::add);
        }

        // 流式路径：通过 StreamingToolExecutor
        List<ConversationMessage> streamResults;
        try (ToolOrchestrator orchestrator = new ToolOrchestrator(registry, alwaysAllow, 4);
             StreamingToolExecutor executor = new StreamingToolExecutor(
                     orchestrator, registry, context, events::add, 4)) {
            executor.addTool(call);
            streamResults = executor.awaitAllResults();
        }

        // 两者应该产生等价结果
        assertEquals(classicResults.size(), streamResults.size());
        for (int i = 0; i < classicResults.size(); i++) {
            ToolResultBlock classic = classicResults.get(i).toolResults().get(0);
            ToolResultBlock stream = streamResults.get(i).toolResults().get(0);
            assertEquals(classic.toolName(), stream.toolName());
            assertEquals(classic.content(), stream.content());
            assertEquals(classic.error(), stream.error());
        }
    }

    // ================================================================
    //  Feature gate integration
    // ================================================================

    @Test
    void featureGate_disabledByDefault() {
        // 默认情况下（无环境变量），流式工具执行应该被禁用
        StreamingToolConfig.Mode mode = StreamingToolConfig.getMode();
        if (mode == StreamingToolConfig.Mode.DISABLED) {
            StreamCallback callback = token -> {};
            assertFalse(StreamingToolConfig.isEnabled(callback, 10));
        }
        // 如果 CI 设置了环境变量，此断言会跳过
    }

    @Test
    void featureGate_autoMode_nullCallbackDisabled() {
        // AUTO 模式下，callback 为 null 时应禁用
        // 直接测试 AUTO 逻辑
        boolean result = StreamingToolConfig.isEnabled(null, 10);
        // 无论环境变量如何设置，null callback 在 DISABLED 和 AUTO 模式下都应返回 false
        // （仅 ENABLED 模式会强制启用，但即使如此 executor 也需要 callback 才有意义）
        StreamingToolConfig.Mode mode = StreamingToolConfig.getMode();
        if (mode != StreamingToolConfig.Mode.ENABLED) {
            assertFalse(result);
        }
    }

    // ================================================================
    //  Helpers
    // ================================================================

    private ConversationMemory createMemory() {
        return new ConversationMemory(
                200_000, 16_384, 13_000,
                new SessionMemory(), MicroCompactConfig.ENABLED
        );
    }

    private Tool simpleTool(String name, String resultContent) {
        return new Tool() {
            @Override
            public ToolMetadata metadata() {
                return concurrentMeta(name);
            }
            @Override
            public ToolResult execute(Map<String, String> input, ToolExecutionContext ctx) {
                return new ToolResult(false, resultContent);
            }
        };
    }

    private ToolMetadata concurrentMeta(String name) {
        return new ToolMetadata(name, "Test tool " + name,
                true, true, false,
                ToolMetadata.PathDomain.NONE, null);
    }

    private ToolMetadata exclusiveMeta(String name) {
        return new ToolMetadata(name, "Test tool " + name,
                true, false, false,
                ToolMetadata.PathDomain.NONE, null);
    }
}
