package com.co.claudecode.demo.mcp.client;

import com.co.claudecode.demo.mcp.McpConnectionState;
import com.co.claudecode.demo.mcp.McpResourceInfo;
import com.co.claudecode.demo.mcp.McpServerConfig;
import com.co.claudecode.demo.mcp.McpServerConnection;
import com.co.claudecode.demo.mcp.McpToolInfo;
import com.co.claudecode.demo.mcp.McpTransportType;
import com.co.claudecode.demo.mcp.transport.McpTransport;
import com.co.claudecode.demo.mcp.auth.JwtTokenProvider;
import com.co.claudecode.demo.mcp.transport.LegacySseTransport;
import com.co.claudecode.demo.mcp.transport.StreamableHttpTransport;
import com.co.claudecode.demo.mcp.transport.StdioTransport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * MCP 多服务器连接管理器。
 * <p>
 * 对应 TS 版 useManageMCPConnections + getMcpToolsCommandsAndResources。
 * 管理多个 MCP 服务器的完整生命周期：连接、发现工具/资源、重连、关闭。
 * <p>
 * 特性：
 * <ul>
 *   <li>批量并发连接（stdio 批次 3，远程批次 10）</li>
 *   <li>远程传输的指数退避重连（最多 5 次）</li>
 *   <li>工具调用路由（根据服务器名分发）</li>
 *   <li>线程安全的连接状态管理</li>
 * </ul>
 */
public final class McpConnectionManager implements AutoCloseable {

    /** stdio 传输并发连接批次大小。 */
    static final int STDIO_BATCH_SIZE = 3;
    /** 远程传输并发连接批次大小。 */
    static final int REMOTE_BATCH_SIZE = 10;
    /** 最大重连尝试次数。 */
    static final int MAX_RECONNECT_ATTEMPTS = 5;
    /** 初始退避毫秒数。 */
    static final long INITIAL_BACKOFF_MS = 1000;
    /** 最大退避毫秒数。 */
    static final long MAX_BACKOFF_MS = 30_000;

    private final Map<String, McpServerConnection> connections = new ConcurrentHashMap<>();
    private final ExecutorService connectionPool;
    private final Consumer<String> eventSink;

    /**
     * @param eventSink 事件输出（日志回调）
     */
    public McpConnectionManager(Consumer<String> eventSink) {
        this.connectionPool = Executors.newFixedThreadPool(
                Math.max(STDIO_BATCH_SIZE, REMOTE_BATCH_SIZE));
        this.eventSink = eventSink != null ? eventSink : s -> {};
    }

    public McpConnectionManager() {
        this(null);
    }

    // ================================================================
    //  批量连接
    // ================================================================

    /**
     * 批量连接所有配置的 MCP 服务器。
     * <p>
     * 按传输类型分批并发连接：
     * <ul>
     *   <li>本地（stdio）：批次大小 {@value STDIO_BATCH_SIZE}</li>
     *   <li>远程（SSE/HTTP）：批次大小 {@value REMOTE_BATCH_SIZE}</li>
     * </ul>
     *
     * @param configs 服务器配置列表
     * @return 所有连接（含失败的）
     */
    public List<McpServerConnection> connectAll(List<McpServerConfig> configs) {
        if (configs == null || configs.isEmpty()) {
            return List.of();
        }

        // 分类
        List<McpServerConfig> localConfigs = new ArrayList<>();
        List<McpServerConfig> remoteConfigs = new ArrayList<>();
        List<McpServerConfig> disabledConfigs = new ArrayList<>();

        for (McpServerConfig config : configs) {
            if (config.disabled()) {
                disabledConfigs.add(config);
            } else if (config.isLocal()) {
                localConfigs.add(config);
            } else {
                remoteConfigs.add(config);
            }
        }

        // 处理 disabled
        for (McpServerConfig config : disabledConfigs) {
            McpServerConnection conn = new McpServerConnection(config.name(), config);
            connections.put(config.name(), conn);
            eventSink.accept("MCP > " + config.name() + " [DISABLED]");
        }

        // 批量连接本地服务器
        connectBatch(localConfigs, STDIO_BATCH_SIZE);

        // 批量连接远程服务器
        connectBatch(remoteConfigs, REMOTE_BATCH_SIZE);

        return List.copyOf(connections.values());
    }

    /**
     * 连接单个服务器。
     */
    public McpServerConnection connect(McpServerConfig config) {
        McpServerConnection conn = new McpServerConnection(config.name(), config);
        connections.put(config.name(), conn);

        if (config.disabled()) {
            eventSink.accept("MCP > " + config.name() + " [DISABLED]");
            return conn;
        }

        doConnect(conn);
        return conn;
    }

    // ================================================================
    //  断开 / 重连
    // ================================================================

    /**
     * 断开指定服务器。
     */
    public void disconnect(String serverName) {
        McpServerConnection conn = connections.get(serverName);
        if (conn != null && conn.client() != null) {
            try {
                conn.client().close();
            } catch (IOException ignored) {
            }
            conn.markFailed("Disconnected by user");
            eventSink.accept("MCP > " + serverName + " disconnected");
        }
    }

    /**
     * 重连指定服务器（带指数退避）。
     */
    public void reconnect(String serverName) {
        McpServerConnection conn = connections.get(serverName);
        if (conn == null) return;

        if (conn.reconnectAttempt() >= MAX_RECONNECT_ATTEMPTS) {
            conn.markFailed("Max reconnect attempts (" + MAX_RECONNECT_ATTEMPTS + ") exceeded");
            eventSink.accept("MCP > " + serverName + " reconnect failed (max attempts)");
            return;
        }

        conn.markPending();
        long backoff = Math.min(
                INITIAL_BACKOFF_MS * (1L << (conn.reconnectAttempt() - 1)),
                MAX_BACKOFF_MS
        );
        eventSink.accept("MCP > " + serverName + " reconnecting (attempt "
                + conn.reconnectAttempt() + ", backoff " + backoff + "ms)");

        try {
            Thread.sleep(backoff);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        doConnect(conn);
    }

    // ================================================================
    //  查询
    // ================================================================

    public List<McpServerConnection> allConnections() {
        return List.copyOf(connections.values());
    }

    public McpServerConnection getConnection(String name) {
        return connections.get(name);
    }

    /**
     * 获取所有已连接服务器的工具。
     */
    public List<McpToolInfo> getAllTools() {
        List<McpToolInfo> allTools = new ArrayList<>();
        for (McpServerConnection conn : connections.values()) {
            if (conn.isConnected()) {
                allTools.addAll(conn.tools());
            }
        }
        return allTools;
    }

    /**
     * 获取所有已连接服务器的资源。
     */
    public List<McpResourceInfo> getAllResources() {
        List<McpResourceInfo> allResources = new ArrayList<>();
        for (McpServerConnection conn : connections.values()) {
            if (conn.isConnected()) {
                allResources.addAll(conn.resources());
            }
        }
        return allResources;
    }

    /**
     * 是否有任何服务器支持资源。
     */
    public boolean hasAnyResources() {
        return connections.values().stream()
                .anyMatch(c -> c.isConnected() && c.hasResources());
    }

    // ================================================================
    //  工具调用路由
    // ================================================================

    /**
     * 路由工具调用到对应的 MCP 服务器。
     *
     * @param serverName 服务器名（规范化后的）
     * @param toolName   工具名（原始名）
     * @param argsJson   参数 JSON
     * @return 调用结果文本
     * @throws IOException 如果调用失败
     */
    public String callTool(String serverName, String toolName, String argsJson) throws IOException {
        McpServerConnection conn = connections.get(serverName);
        if (conn == null) {
            // 尝试按规范化名查找
            for (McpServerConnection c : connections.values()) {
                if (com.co.claudecode.demo.mcp.McpNameUtils.normalizeForMcp(c.name())
                        .equals(serverName)) {
                    conn = c;
                    break;
                }
            }
        }
        if (conn == null) {
            throw new IOException("MCP server not found: " + serverName);
        }
        if (!conn.isConnected() || conn.client() == null) {
            throw new IOException("MCP server not connected: " + serverName);
        }
        return conn.client().callTool(toolName, argsJson);
    }

    /**
     * 路由资源读取到对应的 MCP 服务器。
     */
    public McpResourceContent readResource(String serverName, String uri) throws IOException {
        McpServerConnection conn = connections.get(serverName);
        if (conn == null) {
            throw new IOException("MCP server not found: " + serverName);
        }
        if (!conn.isConnected() || conn.client() == null) {
            throw new IOException("MCP server not connected: " + serverName);
        }
        return conn.client().readResource(uri);
    }

    // ================================================================
    //  关闭
    // ================================================================

    @Override
    public void close() {
        for (McpServerConnection conn : connections.values()) {
            if (conn.client() != null) {
                try {
                    conn.client().close();
                } catch (IOException ignored) {
                }
            }
        }
        connections.clear();
        connectionPool.shutdown();
        try {
            connectionPool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            connectionPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ================================================================
    //  内部方法
    // ================================================================

    private void connectBatch(List<McpServerConfig> configs, int batchSize) {
        for (int i = 0; i < configs.size(); i += batchSize) {
            int end = Math.min(i + batchSize, configs.size());
            List<McpServerConfig> batch = configs.subList(i, end);

            List<Future<?>> futures = new ArrayList<>();
            for (McpServerConfig config : batch) {
                McpServerConnection conn = new McpServerConnection(config.name(), config);
                connections.put(config.name(), conn);
                futures.add(connectionPool.submit(() -> doConnect(conn)));
            }

            // 等待当前批次完成
            for (Future<?> future : futures) {
                try {
                    future.get(60, TimeUnit.SECONDS);
                } catch (Exception e) {
                    // 超时或异常：取消未完成的任务，避免线程泄漏
                    future.cancel(true);
                }
            }
        }
    }

    private void doConnect(McpServerConnection conn) {
        try {
            // 创建传输
            McpTransport transport = createTransport(conn.config());
            transport.start();

            // 创建客户端并握手 — Streamable HTTP 服务器（如 MCPHub）需要 2025-03-26 协议版本
            String protocolVersion = conn.config().transportType() == McpTransportType.HTTP
                    ? McpClient.PROTOCOL_VERSION_2025
                    : McpClient.PROTOCOL_VERSION_2024;
            McpClient client = new McpClient(transport, conn.name(), protocolVersion);
            McpInitResult initResult = client.initialize();

            conn.markConnected(client, initResult);
            eventSink.accept("MCP > " + conn.name() + " [CONNECTED] "
                    + (initResult.serverName() != null ? initResult.serverName() : "")
                    + " v" + (initResult.serverVersion() != null ? initResult.serverVersion() : "?"));

            // 获取工具列表
            if (initResult.supportsTools()) {
                List<McpToolInfo> tools = client.listTools();
                conn.setTools(tools);
                eventSink.accept("MCP > " + conn.name() + " discovered " + tools.size() + " tools");
            }

            // 获取资源列表
            if (initResult.supportsResources()) {
                List<McpResourceInfo> resources = client.listResources();
                conn.setResources(resources);
                eventSink.accept("MCP > " + conn.name() + " discovered "
                        + resources.size() + " resources");
            }

        } catch (Exception e) {
            conn.markFailed(e.getMessage());
            eventSink.accept("MCP > " + conn.name() + " [FAILED] " + e.getMessage());
        }
    }

    private McpTransport createTransport(McpServerConfig config) {
        return switch (config.transportType()) {
            case STDIO -> new StdioTransport(config);
            case SSE -> new LegacySseTransport(config);
            case HTTP -> {
                if (config.auth() != null && config.auth().isValid()) {
                    JwtTokenProvider tokenProvider = new JwtTokenProvider(config.auth(), eventSink);
                    yield new StreamableHttpTransport(config, tokenProvider::getAuthHeaders);
                }
                yield new StreamableHttpTransport(config);
            }
        };
    }
}
