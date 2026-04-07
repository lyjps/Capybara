package com.co.claudecode.demo.mcp;

import com.co.claudecode.demo.message.ToolCallBlock;
import com.co.claudecode.demo.tool.PermissionDecision;
import com.co.claudecode.demo.tool.PermissionPolicy;
import com.co.claudecode.demo.tool.Tool;
import com.co.claudecode.demo.tool.ToolExecutionContext;
import com.co.claudecode.demo.tool.ToolMetadata;
import com.co.claudecode.demo.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * McpPermissionPolicy 单元测试。
 */
class McpPermissionPolicyTest {

    private final ToolExecutionContext ctx = new ToolExecutionContext(Path.of("."), Path.of("output"));

    @Test
    void nonMcpTool_delegatesToInner() {
        PermissionPolicy alwaysDeny = (tool, call, context) -> PermissionDecision.deny("denied");
        McpPermissionPolicy policy = McpPermissionPolicy.allowAll(alwaysDeny);

        ToolCallBlock call = new ToolCallBlock("tc1", "read_file", Map.of());
        PermissionDecision decision = policy.evaluate(dummyTool("read_file"), call, ctx);
        assertFalse(decision.allowed());
        assertEquals("denied", decision.reason());
    }

    @Test
    void mcpTool_allowedByDefault() {
        PermissionPolicy alwaysAllow = (tool, call, context) -> PermissionDecision.allow();
        McpPermissionPolicy policy = McpPermissionPolicy.allowAll(alwaysAllow);

        ToolCallBlock call = new ToolCallBlock("tc1", "mcp__server__tool", Map.of());
        PermissionDecision decision = policy.evaluate(dummyTool("mcp__server__tool"), call, ctx);
        assertTrue(decision.allowed());
    }

    @Test
    void mcpTool_deniedByDenyList() {
        PermissionPolicy alwaysAllow = (tool, call, context) -> PermissionDecision.allow();
        McpPermissionPolicy policy = new McpPermissionPolicy(
                alwaysAllow, Set.of(), Set.of("server"));

        ToolCallBlock call = new ToolCallBlock("tc1", "mcp__server__tool", Map.of());
        PermissionDecision decision = policy.evaluate(dummyTool("mcp__server__tool"), call, ctx);
        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("denied"));
    }

    @Test
    void mcpTool_notInAllowList_denied() {
        PermissionPolicy alwaysAllow = (tool, call, context) -> PermissionDecision.allow();
        McpPermissionPolicy policy = new McpPermissionPolicy(
                alwaysAllow, Set.of("other_server"), Set.of());

        ToolCallBlock call = new ToolCallBlock("tc1", "mcp__server__tool", Map.of());
        PermissionDecision decision = policy.evaluate(dummyTool("mcp__server__tool"), call, ctx);
        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("not in the allowed list"));
    }

    @Test
    void mcpTool_inAllowList_allowed() {
        PermissionPolicy alwaysAllow = (tool, call, context) -> PermissionDecision.allow();
        McpPermissionPolicy policy = new McpPermissionPolicy(
                alwaysAllow, Set.of("server"), Set.of());

        ToolCallBlock call = new ToolCallBlock("tc1", "mcp__server__tool", Map.of());
        PermissionDecision decision = policy.evaluate(dummyTool("mcp__server__tool"), call, ctx);
        assertTrue(decision.allowed());
    }

    @Test
    void mcpTool_emptyAllowList_meansAllAllowed() {
        PermissionPolicy alwaysAllow = (tool, call, context) -> PermissionDecision.allow();
        McpPermissionPolicy policy = new McpPermissionPolicy(
                alwaysAllow, Set.of(), Set.of());

        ToolCallBlock call = new ToolCallBlock("tc1", "mcp__any_server__tool", Map.of());
        PermissionDecision decision = policy.evaluate(dummyTool("mcp__any_server__tool"), call, ctx);
        assertTrue(decision.allowed());
    }

    @Test
    void mcpTool_denyTakesPrecedence() {
        PermissionPolicy alwaysAllow = (tool, call, context) -> PermissionDecision.allow();
        McpPermissionPolicy policy = new McpPermissionPolicy(
                alwaysAllow, Set.of("server"), Set.of("server"));

        ToolCallBlock call = new ToolCallBlock("tc1", "mcp__server__tool", Map.of());
        PermissionDecision decision = policy.evaluate(dummyTool("mcp__server__tool"), call, ctx);
        assertFalse(decision.allowed()); // deny takes precedence
    }

    @Test
    void allowAll_factoryMethod() {
        PermissionPolicy inner = (tool, call, context) -> PermissionDecision.allow();
        McpPermissionPolicy policy = McpPermissionPolicy.allowAll(inner);

        ToolCallBlock call = new ToolCallBlock("tc1", "mcp__any__tool", Map.of());
        assertTrue(policy.evaluate(dummyTool("mcp__any__tool"), call, ctx).allowed());
    }

    private Tool dummyTool(String name) {
        return new Tool() {
            @Override
            public ToolMetadata metadata() {
                return new ToolMetadata(name, "dummy", true, true, false,
                        ToolMetadata.PathDomain.NONE, null, List.of());
            }

            @Override
            public ToolResult execute(Map<String, String> input, ToolExecutionContext context) {
                return new ToolResult(false, "ok");
            }
        };
    }
}
