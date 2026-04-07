package com.co.claudecode.demo.model.llm;

import com.co.claudecode.demo.message.ContentBlock;
import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.message.MessageRole;
import com.co.claudecode.demo.message.SummaryBlock;
import com.co.claudecode.demo.message.TextBlock;
import com.co.claudecode.demo.message.ToolCallBlock;
import com.co.claudecode.demo.message.ToolResultBlock;
import com.co.claudecode.demo.prompt.SystemPromptSections;
import com.co.claudecode.demo.tool.Tool;
import com.co.claudecode.demo.tool.ToolMetadata;
import com.co.claudecode.demo.tool.ToolSearchUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 映射层：把仓库内部消息协议转成 provider 需要的标准化格式。
 * <p>
 * 关键职责：
 * 1. 把 ToolCallBlock / ToolResultBlock 正确映射
 * 2. 把 compact 产生的 SummaryBlock 合并到 system prompt（而不是作为消息发给 API）
 * 3. 确保发给 API 的消息中 tool_use / tool_result 严格配对
 */
public final class LlmConversationMapper {

    /**
     * 标准转换（不含延迟加载过滤，全量发送工具 schema）。
     */
    public LlmRequest toRequest(List<ConversationMessage> conversation, String modelName,
                                Collection<Tool> tools) {
        return toRequest(conversation, modelName, tools, tools);
    }

    /**
     * 带延迟加载的转换：{@code allTools} 用于判定 deferLoading 标志，
     * {@code filteredTools} 是实际要发送 schema 的工具子集。
     * <p>
     * 对应 TS {@code claude.ts} 中 queryModel 的逻辑：
     * <ul>
     *   <li>{@code filteredTools} 由 {@link ToolSearchUtils#filterToolsForApi} 过滤得到</li>
     *   <li>每个 schema 的 {@code deferLoading} 标志由 {@link ToolSearchUtils#willDeferLoading} 决定</li>
     * </ul>
     *
     * @param conversation  完整会话消息列表
     * @param modelName     模型名
     * @param filteredTools 经过延迟加载过滤后的工具集合（实际发送 schema 的子集）
     * @param allTools      全量工具集合（用于判定 deferLoading 标志）
     */
    public LlmRequest toRequest(List<ConversationMessage> conversation, String modelName,
                                Collection<Tool> filteredTools, Collection<Tool> allTools) {

        // 收集 system prompt 和 summary（compact 产物）
        StringBuilder systemPrompt = new StringBuilder();
        for (ConversationMessage msg : conversation) {
            if (msg.role() == MessageRole.SYSTEM) {
                String text = extractSystemText(msg);
                if (!text.isBlank()) {
                    if (!systemPrompt.isEmpty()) systemPrompt.append("\n\n");
                    systemPrompt.append(text);
                }
            }
        }
        if (systemPrompt.isEmpty()) {
            systemPrompt.append(SystemPromptSections.ROLE_AND_GUIDELINES);
        }

        // 收集非 SYSTEM 消息
        List<LlmMessage> rawMessages = new ArrayList<>();
        for (ConversationMessage msg : conversation) {
            if (msg.role() == MessageRole.SYSTEM) {
                continue;
            }
            rawMessages.addAll(mapMessage(msg));
        }

        // 修复配对：确保每个 tool_result 都有对应的 tool_use
        List<LlmMessage> messages = ensureToolPairing(rawMessages);

        // 构建工具 schema，带 deferLoading 标志
        List<LlmRequest.ToolSchema> toolSchemas = filteredTools.stream()
                .map(tool -> mapToolWithDeferFlag(tool, allTools))
                .toList();

        return new LlmRequest(systemPrompt.toString(), messages, modelName, toolSchemas);
    }

    /**
     * 提取 SYSTEM 消息中的文本（包括 SummaryBlock）。
     */
    private String extractSystemText(ConversationMessage msg) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : msg.blocks()) {
            if (block instanceof TextBlock tb) {
                if (!sb.isEmpty()) sb.append("\n");
                sb.append(tb.text());
            } else if (block instanceof SummaryBlock summary) {
                if (!sb.isEmpty()) sb.append("\n");
                sb.append("[Earlier conversation summary]\n").append(summary.summary());
            }
        }
        return sb.toString().trim();
    }

    /**
     * 将单个 ConversationMessage 映射为一个或多个 LlmMessage。
     */
    private List<LlmMessage> mapMessage(ConversationMessage msg) {
        String role = msg.role().name().toLowerCase();

        // 检查是否包含 tool_result
        List<ToolResultBlock> toolResults = msg.toolResults();
        if (!toolResults.isEmpty()) {
            return toolResults.stream()
                    .map(tr -> new LlmMessage("user", tr.content(),
                            LlmMessage.Type.TOOL_RESULT, tr.toolUseId(), tr.toolName(), tr.error()))
                    .toList();
        }

        // 检查是否包含 tool_call（assistant 消息）
        List<ToolCallBlock> toolCalls = msg.toolCalls();
        if (!toolCalls.isEmpty()) {
            String text = msg.plainText();
            return List.of(new LlmMessage("assistant", text,
                    LlmMessage.Type.TOOL_CALLS, toolCalls));
        }

        // 纯文本消息
        return List.of(new LlmMessage(role, msg.plainText()));
    }

    /**
     * 确保发送给 API 的消息中 tool_use 和 tool_result 严格配对。
     * <p>
     * Anthropic API 要求：每个 tool_result 的 tool_use_id 必须对应前一条
     * assistant 消息中的某个 tool_use block。如果 compact 丢掉了中间的
     * assistant 消息，孤立的 tool_result 会导致 400 错误。
     * <p>
     * 策略：先收集所有已出现的 tool_use id，再过滤掉孤立的 tool_result。
     */
    private List<LlmMessage> ensureToolPairing(List<LlmMessage> messages) {
        // 第一遍：收集所有已发出的 tool_use id
        Set<String> availableToolUseIds = new HashSet<>();
        for (LlmMessage msg : messages) {
            if (msg.type() == LlmMessage.Type.TOOL_CALLS) {
                for (ToolCallBlock tc : msg.toolCalls()) {
                    availableToolUseIds.add(tc.id());
                }
            }
        }

        // 第二遍：过滤掉找不到对应 tool_use 的 tool_result
        List<LlmMessage> result = new ArrayList<>();
        for (LlmMessage msg : messages) {
            if (msg.type() == LlmMessage.Type.TOOL_RESULT) {
                if (msg.toolUseId() != null && availableToolUseIds.contains(msg.toolUseId())) {
                    result.add(msg);
                }
                // else: 孤立的 tool_result，被 compact 后丢失了对应的 tool_use，跳过
            } else {
                result.add(msg);
            }
        }

        return result;
    }

    /**
     * 将工具映射为带 deferLoading 标志的 schema。
     */
    private LlmRequest.ToolSchema mapToolWithDeferFlag(Tool tool, Collection<Tool> allTools) {
        ToolMetadata meta = tool.metadata();
        List<LlmRequest.ToolSchema.ParamSchema> params = meta.params().stream()
                .map(p -> new LlmRequest.ToolSchema.ParamSchema(p.name(), p.description(), p.required()))
                .toList();
        boolean deferLoading = ToolSearchUtils.willDeferLoading(tool, allTools);
        return new LlmRequest.ToolSchema(meta.name(), meta.description(), params, deferLoading);
    }
}
