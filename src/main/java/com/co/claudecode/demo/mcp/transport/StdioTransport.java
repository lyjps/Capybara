package com.co.claudecode.demo.mcp.transport;

import com.co.claudecode.demo.mcp.McpServerConfig;
import com.co.claudecode.demo.mcp.protocol.JsonRpcRequest;
import com.co.claudecode.demo.mcp.protocol.JsonRpcResponse;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 子进程 stdin/stdout 传输层。
 * <p>
 * 对应 TS 版 StdioClientTransport。使用 ProcessBuilder 启动子进程，
 * 通过 stdin 发送 JSON-RPC 请求，通过 stdout 接收响应。
 * 每行一个 JSON 消息（newline-delimited JSON）。
 * <p>
 * 关闭时遵循 SIGTERM → 等待 → SIGKILL 的优雅关闭序列。
 */
public final class StdioTransport implements McpTransport {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final long GRACEFUL_SHUTDOWN_MS = 500;

    private final McpServerConfig config;
    private Process process;
    private BufferedWriter stdin;
    private BufferedReader stdout;
    private BufferedReader stderr;
    private volatile boolean open;

    public StdioTransport(McpServerConfig config) {
        this.config = config;
    }

    @Override
    public void start() throws IOException {
        List<String> command = new ArrayList<>();
        command.add(config.command());
        command.addAll(config.args());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);

        // 设置环境变量
        Map<String, String> processEnv = pb.environment();
        if (!config.env().isEmpty()) {
            processEnv.putAll(config.env());
        }

        process = pb.start();
        stdin = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        stdout = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        stderr = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));

        // 启动 stderr 消费线程（防止缓冲区阻塞）
        Thread stderrThread = new Thread(this::consumeStderr, "mcp-stderr-" + config.name());
        stderrThread.setDaemon(true);
        stderrThread.start();

        // 验证进程是否存活
        if (!process.isAlive()) {
            String stderrContent = readAvailableStderr();
            throw new IOException("MCP server process exited immediately. stderr: " + stderrContent);
        }

        open = true;
    }

    @Override
    public JsonRpcResponse sendRequest(JsonRpcRequest request) throws IOException {
        checkOpen();

        String json = request.toJson();
        synchronized (this) {
            // 写入请求
            stdin.write(json);
            stdin.newLine();
            stdin.flush();

            // 读取响应（阻塞直到收到一行）
            String responseLine = stdout.readLine();
            if (responseLine == null) {
                throw new IOException("MCP server process closed stdout (process may have exited)");
            }

            return JsonRpcResponse.parse(responseLine);
        }
    }

    @Override
    public void sendNotification(String method, String params) throws IOException {
        checkOpen();

        // 通知没有 id 字段
        StringBuilder sb = new StringBuilder();
        sb.append("{\"jsonrpc\":\"2.0\",\"method\":\"")
                .append(method).append("\"");
        if (params != null) {
            sb.append(",\"params\":").append(params);
        }
        sb.append("}");

        synchronized (this) {
            stdin.write(sb.toString());
            stdin.newLine();
            stdin.flush();
        }
    }

    @Override
    public boolean isOpen() {
        return open && process != null && process.isAlive();
    }

    @Override
    public void close() throws IOException {
        open = false;
        if (process != null) {
            try {
                // 尝试优雅关闭
                if (stdin != null) {
                    try {
                        stdin.close();
                    } catch (IOException ignored) {
                    }
                }

                // SIGTERM
                process.destroy();
                boolean terminated = false;
                try {
                    terminated = process.waitFor(GRACEFUL_SHUTDOWN_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // SIGKILL if needed
                if (!terminated) {
                    process.destroyForcibly();
                    try {
                        process.waitFor(GRACEFUL_SHUTDOWN_MS, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            } finally {
                closeQuietly(stdout);
                closeQuietly(stderr);
                process = null;
                stdin = null;
                stdout = null;
                stderr = null;
            }
        }
    }

    private void checkOpen() throws IOException {
        if (!isOpen()) {
            throw new IOException("StdioTransport is not open");
        }
    }

    private void consumeStderr() {
        try {
            String line;
            while ((line = stderr.readLine()) != null) {
                // 静默消费 stderr，防止缓冲区满
                // 在生产实现中应写入日志
                System.err.println("[MCP:" + config.name() + " stderr] " + line);
            }
        } catch (IOException ignored) {
            // 进程关闭时正常退出
        }
    }

    private String readAvailableStderr() {
        if (stderr == null) return "";
        try {
            StringBuilder sb = new StringBuilder();
            while (stderr.ready()) {
                String line = stderr.readLine();
                if (line != null) {
                    if (!sb.isEmpty()) sb.append("\n");
                    sb.append(line);
                }
            }
            return sb.toString();
        } catch (IOException e) {
            return "(failed to read stderr)";
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }
}
