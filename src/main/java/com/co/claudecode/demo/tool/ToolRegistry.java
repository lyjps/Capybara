package com.co.claudecode.demo.tool;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 保持注册表这个中间层，是为了让 agent loop 永远通过“名字”找能力。
 * 这样模型侧、权限侧、执行侧不会直接持有具体实现类，从一开始就更容易扩展。
 */
public final class ToolRegistry {

    private final Map<String, Tool> toolsByName = new LinkedHashMap<>();

    public ToolRegistry(Collection<Tool> tools) {
        for (Tool tool : tools) {
            toolsByName.put(tool.metadata().name(), tool);
        }
    }

    public Tool require(String toolName) {
        Tool tool = toolsByName.get(toolName);
        if (tool == null) {
            throw new IllegalArgumentException("Unknown tool: " + toolName);
        }
        return tool;
    }

    public Collection<Tool> allTools() {
        return toolsByName.values();
    }
}
