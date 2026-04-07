package com.co.claudecode.demo.prompt;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Collects runtime environment information for the dynamic env_info prompt section.
 * <p>
 * Mirrors the TS {@code computeSimpleEnvInfo()} from {@code src/constants/prompts.ts}.
 */
public final class EnvironmentInfo {

    private EnvironmentInfo() {
    }

    /**
     * Collect environment information as a formatted prompt section.
     *
     * @param cwd       current working directory
     * @param modelName model identifier (nullable)
     * @return formatted environment info section
     */
    public static String collect(Path cwd, String modelName) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Environment\n");
        sb.append("You have been invoked in the following environment:\n");
        sb.append(" - Primary working directory: ").append(cwd.toAbsolutePath()).append('\n');
        sb.append(" - Is a git repository: ").append(isGitRepo(cwd)).append('\n');
        sb.append(" - Platform: ").append(getPlatform()).append('\n');
        sb.append(" - Java version: ").append(System.getProperty("java.version", "unknown")).append('\n');

        String shell = System.getenv("SHELL");
        if (shell != null && !shell.isBlank()) {
            String shellName = shell.contains("zsh") ? "zsh"
                    : shell.contains("bash") ? "bash"
                    : shell;
            sb.append(" - Shell: ").append(shellName).append('\n');
        }

        if (modelName != null && !modelName.isBlank()) {
            sb.append(" - Model: ").append(modelName).append('\n');
        }

        // Strip trailing newline
        if (sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }

        return sb.toString();
    }

    /**
     * Check if the given path is inside a git repository by walking up
     * the directory tree looking for a {@code .git} directory.
     */
    public static boolean isGitRepo(Path dir) {
        Path current = dir.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve(".git"))) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    /**
     * Returns platform string in "OS arch" format (e.g., "Mac OS X aarch64").
     */
    static String getPlatform() {
        String os = System.getProperty("os.name", "unknown");
        String arch = System.getProperty("os.arch", "");
        return arch.isBlank() ? os : os + " " + arch;
    }
}
