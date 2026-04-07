/**
 * Skill System -- user-configured skill loading and execution.
 * <p>
 * Skills are Markdown files with YAML frontmatter metadata, stored in:
 * <ul>
 *   <li>{@code ~/.claude/skills/} -- user-level skills</li>
 *   <li>{@code <project>/.claude/skills/} -- project-level skills (override user-level)</li>
 * </ul>
 * <p>
 * Two execution modes:
 * <ul>
 *   <li><b>inline</b> (default): inject prompt into current conversation</li>
 *   <li><b>fork</b>: execute in a sub-agent with independent context</li>
 * </ul>
 * <p>
 * Invocation paths:
 * <ul>
 *   <li>Model calls {@code Skill} tool with skill name + args</li>
 *   <li>User types {@code /skill_name args} in REPL</li>
 * </ul>
 */
package com.co.claudecode.demo.skill;
