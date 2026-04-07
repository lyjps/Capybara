package com.co.claudecode.demo.mcp;

import com.co.claudecode.demo.message.ToolCallBlock;
import com.co.claudecode.demo.tool.PermissionDecision;
import com.co.claudecode.demo.tool.PermissionPolicy;
import com.co.claudecode.demo.tool.Tool;
import com.co.claudecode.demo.tool.ToolExecutionContext;

import java.util.Set;

/**
 * MCP 感知的权限策略包装器。
 * <p>
 * 对应 TS 版 permissions.ts 中 MCP 工具的 passthrough + 规则匹配。
 * 在原有权限策略的基础上添加 MCP 服务器级别的允许/拒绝控制。
 * <p>
 * 权限判定流程：
 * <ol>
 *   <li>如果不是 MCP 工具 → 委托给原有策略</li>
 *   <li>解析服务器名</li>
 *   <li>检查拒绝列表 → 拒绝</li>
 *   <li>检查允许列表（非空时）→ 不在列表中则拒绝</li>
 *   <li>通过 → 允许</li>
 * </ol>
 */
public final class McpPermissionPolicy implements PermissionPolicy {

    private final PermissionPolicy delegate;
    private final Set<String> allowedServers;
    private final Set<String> deniedServers;

    /**
     * @param delegate       原有权限策略
     * @param allowedServers 允许的服务器名集合（空集 = 全部允许）
     * @param deniedServers  拒绝的服务器名集合
     */
    public McpPermissionPolicy(PermissionPolicy delegate,
                               Set<String> allowedServers,
                               Set<String> deniedServers) {
        this.delegate = delegate;
        this.allowedServers = allowedServers != null ? Set.copyOf(allowedServers) : Set.of();
        this.deniedServers = deniedServers != null ? Set.copyOf(deniedServers) : Set.of();
    }

    /**
     * 创建无 MCP 限制的策略（所有 MCP 工具都允许）。
     */
    public static McpPermissionPolicy allowAll(PermissionPolicy delegate) {
        return new McpPermissionPolicy(delegate, Set.of(), Set.of());
    }

    @Override
    public PermissionDecision evaluate(Tool tool, ToolCallBlock call, ToolExecutionContext context) {
        String toolName = call.toolName();

        // 非 MCP 工具 → 委托
        if (!McpNameUtils.isMcpTool(toolName)) {
            return delegate.evaluate(tool, call, context);
        }

        // 解析服务器名
        McpNameUtils.McpToolRef ref = McpNameUtils.parseToolName(toolName);
        if (ref == null) {
            return delegate.evaluate(tool, call, context);
        }

        // 检查拒绝列表
        if (!deniedServers.isEmpty() && deniedServers.contains(ref.serverName())) {
            return PermissionDecision.deny(
                    "MCP server '" + ref.serverName() + "' is denied by policy");
        }

        // 检查允许列表（非空时限制）
        if (!allowedServers.isEmpty() && !allowedServers.contains(ref.serverName())) {
            return PermissionDecision.deny(
                    "MCP server '" + ref.serverName() + "' is not in the allowed list");
        }

        // MCP 工具通过 → 允许
        return PermissionDecision.allow();
    }
}
