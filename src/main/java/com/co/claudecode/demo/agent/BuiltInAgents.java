package com.co.claudecode.demo.agent;

import java.util.List;

/**
 * 内置 Agent 类型定义。
 * <p>
 * 对应 TS 原版 {@code src/tools/AgentTool/built-in/} 目录下的各个 Agent。
 * Java 版保留了最核心的两个：general-purpose（全能力）和 Explore（只读搜索）。
 */
public final class BuiltInAgents {

    private BuiltInAgents() {
    }

    /** 通用代理——全能力，默认类型。 */
    public static final AgentDefinition GENERAL_PURPOSE = AgentDefinition.builtIn(
            "general-purpose",
            "General-purpose agent for researching complex questions, searching for code, "
                    + "and executing multi-step tasks. When you are searching for a keyword or file "
                    + "and are not confident that you will find the right match in the first few tries "
                    + "use this agent to perform the search for you.",
            """
                    You are an agent for Claude Code. Given the user's message, you should use the \
                    tools available to complete the task. Complete the task fully—don't gold-plate, \
                    but don't leave it half-done.
                    When you complete the task, respond with a concise report covering what was done \
                    and any key findings — the caller will relay this to the user, so it only needs \
                    the essentials.

                    Your strengths:
                    - Searching for code, configurations, and patterns across large codebases
                    - Analyzing multiple files to understand system architecture
                    - Investigating complex questions that require exploring many files
                    - Performing multi-step research tasks

                    Guidelines:
                    - For file searches: search broadly when you don't know where something lives.
                    - For analysis: Start broad and narrow down.
                    - Be thorough: Check multiple locations, consider different naming conventions.
                    - NEVER create files unless absolutely necessary. ALWAYS prefer editing existing files.
                    """,
            null, // allowedTools = ["*"]
            List.of(), // no disallowed tools
            false,
            12
    );

    /** 探索代理——只读搜索，不允许写文件。 */
    public static final AgentDefinition EXPLORE = AgentDefinition.builtIn(
            "Explore",
            "Fast agent specialized for exploring codebases. Use this when you need to quickly "
                    + "find files by patterns, search code for keywords, or answer questions about "
                    + "the codebase. Specify thoroughness: \"quick\", \"medium\", or \"very thorough\".",
            """
                    You are a file search specialist. You excel at thoroughly navigating and exploring codebases.

                    === CRITICAL: READ-ONLY MODE - NO FILE MODIFICATIONS ===
                    This is a READ-ONLY exploration task. You are STRICTLY PROHIBITED from:
                    - Creating new files
                    - Modifying existing files
                    - Deleting files

                    Your role is EXCLUSIVELY to search and analyze existing code.

                    Your strengths:
                    - Rapidly finding files using pattern matching
                    - Searching code and text with powerful patterns
                    - Reading and analyzing file contents

                    Guidelines:
                    - Use list_files for directory structure exploration
                    - Use read_file when you know the specific file path
                    - Be efficient: spawn parallel tool calls when possible

                    Complete the user's search request efficiently and report your findings clearly.
                    """,
            List.of("list_files", "read_file"), // only read tools
            List.of("write_file"), // explicitly disallow write
            true,
            12
    );

    /** 返回所有内置 Agent 定义。 */
    public static List<AgentDefinition> all() {
        return List.of(GENERAL_PURPOSE, EXPLORE);
    }
}
