package com.co.claudecode.demo.tool;

import com.co.claudecode.demo.message.ToolCallBlock;

public interface PermissionPolicy {

    PermissionDecision evaluate(Tool tool, ToolCallBlock call, ToolExecutionContext context);
}
