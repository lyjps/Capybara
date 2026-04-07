package com.co.claudecode.demo.skill;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SkillRegistry} — lookup, listing, and system prompt generation.
 */
class SkillRegistryTest {

    private static final Path DUMMY_PATH = Path.of("/tmp/test.md");

    private SkillDefinition makeSkill(String name, String description, String whenToUse) {
        return new SkillDefinition(name, description, whenToUse, null, null, null, null,
                "prompt", DUMMY_PATH, SkillDefinition.Source.USER);
    }

    private SkillDefinition makeSkillWithArgs(String name, List<SkillDefinition.SkillArgument> args) {
        return new SkillDefinition(name, "desc", "", null, null, null, args,
                "prompt", DUMMY_PATH, SkillDefinition.Source.USER);
    }

    // ================================================================
    //  findByName
    // ================================================================

    @Test
    void findByNameExactMatch() {
        var registry = new SkillRegistry(List.of(
                makeSkill("alpha", "A", ""),
                makeSkill("beta", "B", "")
        ));

        assertEquals("alpha", registry.findByName("alpha").name());
        assertEquals("beta", registry.findByName("beta").name());
    }

    @Test
    void findByNameReturnsNullForUnknown() {
        var registry = new SkillRegistry(List.of(makeSkill("alpha", "", "")));
        assertNull(registry.findByName("gamma"));
    }

    @Test
    void findByNameReturnsNullForNull() {
        var registry = new SkillRegistry(List.of(makeSkill("alpha", "", "")));
        assertNull(registry.findByName(null));
    }

    @Test
    void findByNameStripsMdExtension() {
        var registry = new SkillRegistry(List.of(makeSkill("my-skill", "", "")));
        assertNotNull(registry.findByName("my-skill.md"));
        assertEquals("my-skill", registry.findByName("my-skill.md").name());
    }

    @Test
    void findByNameDoesNotStripNonMdExtension() {
        var registry = new SkillRegistry(List.of(makeSkill("my-skill", "", "")));
        assertNull(registry.findByName("my-skill.txt"));
    }

    // ================================================================
    //  allSkills / hasSkills / size
    // ================================================================

    @Test
    void allSkillsReturnsAllInOrder() {
        var registry = new SkillRegistry(List.of(
                makeSkill("first", "", ""),
                makeSkill("second", "", "")
        ));

        List<SkillDefinition> all = registry.allSkills();
        assertEquals(2, all.size());
        assertEquals("first", all.get(0).name());
        assertEquals("second", all.get(1).name());
    }

    @Test
    void hasSkillsTrueWhenPopulated() {
        var registry = new SkillRegistry(List.of(makeSkill("x", "", "")));
        assertTrue(registry.hasSkills());
    }

    @Test
    void hasSkillsFalseWhenEmpty() {
        var registry = new SkillRegistry(List.of());
        assertFalse(registry.hasSkills());
    }

    @Test
    void sizeReturnsCorrectCount() {
        var registry = new SkillRegistry(List.of(
                makeSkill("a", "", ""),
                makeSkill("b", "", ""),
                makeSkill("c", "", "")
        ));
        assertEquals(3, registry.size());
    }

    @Test
    void emptyRegistrySize() {
        assertEquals(0, new SkillRegistry(List.of()).size());
    }

    // ================================================================
    //  buildSkillListing
    // ================================================================

    @Test
    void buildSkillListingContainsAllSkills() {
        var registry = new SkillRegistry(List.of(
                makeSkill("code-review", "Reviews code quality", "When code needs review"),
                makeSkill("deploy", "Deploys to production", "")
        ));

        String listing = registry.buildSkillListing();

        assertTrue(listing.contains("code-review"));
        assertTrue(listing.contains("Reviews code quality"));
        assertTrue(listing.contains("使用场景: When code needs review"));
        assertTrue(listing.contains("deploy"));
        assertTrue(listing.contains("Deploys to production"));
    }

    @Test
    void buildSkillListingIncludesArguments() {
        var registry = new SkillRegistry(List.of(
                makeSkillWithArgs("my-skill", List.of(
                        new SkillDefinition.SkillArgument("file", "Target file", true),
                        new SkillDefinition.SkillArgument("verbose", "Verbose output", false)
                ))
        ));

        String listing = registry.buildSkillListing();

        assertTrue(listing.contains("file (required)"));
        assertTrue(listing.contains("Target file"));
        assertTrue(listing.contains("verbose"));
        assertTrue(listing.contains("Verbose output"));
    }

    @Test
    void buildSkillListingEmptyRegistryReturnsEmpty() {
        var registry = new SkillRegistry(List.of());
        assertEquals("", registry.buildSkillListing());
    }

    @Test
    void buildSkillListingHasHeader() {
        var registry = new SkillRegistry(List.of(makeSkill("test", "", "")));
        String listing = registry.buildSkillListing();
        assertTrue(listing.contains("skills 可通过 Skill 工具调用"));
    }

    // ================================================================
    //  Duplicate name handling
    // ================================================================

    @Test
    void laterSkillOverridesEarlierWithSameName() {
        var registry = new SkillRegistry(List.of(
                makeSkill("dup", "First", ""),
                makeSkill("dup", "Second", "")
        ));

        assertEquals(1, registry.size());
        assertEquals("Second", registry.findByName("dup").description());
    }
}
