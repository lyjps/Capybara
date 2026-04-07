/**
 * Streaming Tool Execution — 流式工具执行包。
 * <p>
 * 对应 TS {@code StreamingToolExecutor.ts} 的核心逻辑：
 * 在 SSE 流式输出过程中，每当一个 tool_use content block 完成（content_block_stop），
 * 就立即开始执行该工具，与模型继续输出下一个 block 并行，显著减少端到端延迟。
 * <p>
 * 核心组件：
 * <ul>
 *   <li>{@link com.co.claudecode.demo.tool.streaming.StreamingToolExecutor} — 工具调度器（状态机 + 并发控制）</li>
 *   <li>{@link com.co.claudecode.demo.tool.streaming.StreamingToolCallback} — SSE 回调扩展接口</li>
 *   <li>{@link com.co.claudecode.demo.tool.streaming.StreamingToolConfig} — Feature gate 配置</li>
 * </ul>
 */
package com.co.claudecode.demo.tool.streaming;
