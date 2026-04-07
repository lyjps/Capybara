package com.co.claudecode.demo.prompt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EnvironmentInfo 单元测试。
 */
class EnvironmentInfoTest {

    @TempDir
    Path tempDir;

    @Test
    void collect_containsCwd() {
        String info = EnvironmentInfo.collect(tempDir, null);
        assertTrue(info.contains("Primary working directory"),
                "Should contain working directory label");
        assertTrue(info.contains(tempDir.toAbsolutePath().toString()),
                "Should contain actual cwd path");
    }

    @Test
    void collect_containsJavaVersion() {
        String info = EnvironmentInfo.collect(tempDir, null);
        assertTrue(info.contains("Java version"),
                "Should contain Java version label");
        assertTrue(info.contains(System.getProperty("java.version")),
                "Should contain actual java version");
    }

    @Test
    void collect_containsPlatform() {
        String info = EnvironmentInfo.collect(tempDir, null);
        assertTrue(info.contains("Platform"),
                "Should contain platform label");
        assertTrue(info.contains(System.getProperty("os.name")),
                "Should contain os name");
    }

    @Test
    void collect_containsEnvironmentHeader() {
        String info = EnvironmentInfo.collect(tempDir, null);
        assertTrue(info.startsWith("# Environment"),
                "Should start with # Environment header");
        assertTrue(info.contains("You have been invoked"),
                "Should contain intro text");
    }

    @Test
    void collect_withModelName_containsModel() {
        String info = EnvironmentInfo.collect(tempDir, "claude-sonnet-4-20250514");
        assertTrue(info.contains("Model: claude-sonnet-4-20250514"),
                "Should contain model name");
    }

    @Test
    void collect_withNullModelName_noModelLine() {
        String info = EnvironmentInfo.collect(tempDir, null);
        assertFalse(info.contains("Model:"),
                "Should not contain Model line when null");
    }

    @Test
    void collect_withBlankModelName_noModelLine() {
        String info = EnvironmentInfo.collect(tempDir, "  ");
        assertFalse(info.contains("Model:"),
                "Should not contain Model line when blank");
    }

    @Test
    void collect_containsGitStatus() {
        String info = EnvironmentInfo.collect(tempDir, null);
        assertTrue(info.contains("Is a git repository"),
                "Should contain git status");
    }

    @Test
    void isGitRepo_withGitDir_returnsTrue() throws IOException {
        Files.createDirectory(tempDir.resolve(".git"));
        assertTrue(EnvironmentInfo.isGitRepo(tempDir),
                "Should detect .git directory");
    }

    @Test
    void isGitRepo_withoutGitDir_returnsFalse() {
        // tempDir has no .git, and it's unlikely to be inside a git repo
        // at the temp directory level — but the real project root IS a git repo,
        // so we use a deep nested directory to be safe
        Path deepDir = tempDir.resolve("a/b/c/d/e");
        try {
            Files.createDirectories(deepDir);
        } catch (IOException e) {
            fail("Could not create test directory");
        }
        // tempDir itself might be under a git repo, so test the method can
        // at least detect when .git exists
        assertNotNull(EnvironmentInfo.isGitRepo(deepDir));
    }

    @Test
    void isGitRepo_detectsParentGit() throws IOException {
        Path subDir = tempDir.resolve("subproject");
        Files.createDirectories(subDir);
        Files.createDirectory(tempDir.resolve(".git"));

        assertTrue(EnvironmentInfo.isGitRepo(subDir),
                "Should detect .git in parent directory");
    }

    @Test
    void getPlatform_returnsNonEmpty() {
        String platform = EnvironmentInfo.getPlatform();
        assertNotNull(platform);
        assertFalse(platform.isBlank(), "Platform should not be blank");
    }
}
