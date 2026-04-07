package com.co.claudecode.demo.mcp;

import com.co.claudecode.demo.mcp.client.McpClient;
import com.co.claudecode.demo.mcp.client.McpInitResult;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MCP 服务器连接状态持有者。
 * <p>
 * 跟踪单个 MCP 服务器的完整连接生命周期：状态、客户端实例、
 * 已发现的工具和资源、服务器信息、错误消息。
 * <p>
 * 对应 TS 版 MCPServerConnection 的简化版本。
 */
public final class McpServerConnection {

    private final String name;
    private final McpServerConfig config;
    private volatile McpConnectionState state;
    private McpClient client;
    private final List<McpToolInfo> tools = new CopyOnWriteArrayList<>();
    private final List<McpResourceInfo> resources = new CopyOnWriteArrayList<>();
    private String serverName;
    private String serverVersion;
    private String instructions;
    private String errorMessage;
    private int reconnectAttempt;

    public McpServerConnection(String name, McpServerConfig config) {
        this.name = name;
        this.config = config;
        this.state = config.disabled() ? McpConnectionState.DISABLED : McpConnectionState.PENDING;
    }

    // ---- State transitions ----

    public void markConnected(McpClient client, McpInitResult initResult) {
        this.client = client;
        this.state = McpConnectionState.CONNECTED;
        this.serverName = initResult.serverName();
        this.serverVersion = initResult.serverVersion();
        this.instructions = initResult.instructions();
        this.errorMessage = null;
        this.reconnectAttempt = 0;
    }

    public void markFailed(String error) {
        this.state = McpConnectionState.FAILED;
        this.errorMessage = error;
    }

    public void markPending() {
        this.state = McpConnectionState.PENDING;
        this.reconnectAttempt++;
    }

    public void markDisabled() {
        this.state = McpConnectionState.DISABLED;
    }

    public void setTools(List<McpToolInfo> tools) {
        this.tools.clear();
        this.tools.addAll(tools);
    }

    public void setResources(List<McpResourceInfo> resources) {
        this.resources.clear();
        this.resources.addAll(resources);
    }

    // ---- Getters ----

    public String name() {
        return name;
    }

    public McpServerConfig config() {
        return config;
    }

    public McpConnectionState state() {
        return state;
    }

    public McpClient client() {
        return client;
    }

    public List<McpToolInfo> tools() {
        return List.copyOf(tools);
    }

    public List<McpResourceInfo> resources() {
        return List.copyOf(resources);
    }

    public String serverName() {
        return serverName;
    }

    public String serverVersion() {
        return serverVersion;
    }

    public String instructions() {
        return instructions;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public int reconnectAttempt() {
        return reconnectAttempt;
    }

    public boolean isConnected() {
        return state == McpConnectionState.CONNECTED;
    }

    public boolean hasResources() {
        return !resources.isEmpty();
    }

    /**
     * 格式化连接状态信息。
     */
    public String formatStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append(" [").append(state).append("]");
        sb.append(" transport=").append(config.transportType().name().toLowerCase());
        if (state == McpConnectionState.CONNECTED) {
            sb.append(" tools=").append(tools.size());
            sb.append(" resources=").append(resources.size());
            if (serverVersion != null) {
                sb.append(" version=").append(serverVersion);
            }
        } else if (state == McpConnectionState.FAILED && errorMessage != null) {
            sb.append(" error=").append(errorMessage);
        }
        return sb.toString();
    }
}
