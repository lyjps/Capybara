package com.co.claudecode.demo.mcp.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolMapping 单元测试。
 */
class ToolMappingTest {

    // ---- 字面映射 ----

    @Test
    void literal_resolveUpstreamName() {
        ToolMapping mapping = ToolMapping.literal("meituan_search_mix", "offline_meituan_search_mix");
        assertEquals("offline_meituan_search_mix",
                mapping.resolveUpstreamName(Map.of("queries", "[\"火锅\"]")));
    }

    @Test
    void literal_isNotTemplate() {
        ToolMapping mapping = ToolMapping.literal("test", "upstream_test");
        assertFalse(mapping.isTemplate());
    }

    @Test
    void literal_withStripAndInject() {
        ToolMapping mapping = ToolMapping.literal("exposed", "upstream",
                List.of("originalQuery"), Map.of("lat", 40.0));
        assertEquals(List.of("originalQuery"), mapping.stripParams());
        assertEquals(Map.of("lat", 40.0), mapping.injectParams());
    }

    // ---- 模板映射 ----

    @Test
    void template_isTemplate() {
        ToolMapping mapping = ToolMapping.template("mt_map_direction", "{mode}", "mode");
        assertTrue(mapping.isTemplate());
    }

    @Test
    void template_resolveUpstreamName_driving() {
        ToolMapping mapping = ToolMapping.template("mt_map_direction", "{mode}", "mode");
        assertEquals("driving", mapping.resolveUpstreamName(Map.of("mode", "driving")));
    }

    @Test
    void template_resolveUpstreamName_walking() {
        ToolMapping mapping = ToolMapping.template("mt_map_direction", "{mode}", "mode");
        assertEquals("walking", mapping.resolveUpstreamName(Map.of("mode", "walking")));
    }

    @Test
    void template_resolveUpstreamName_distanceMatrix() {
        ToolMapping mapping = ToolMapping.template("mt_map_distance", "{mode}distancematrix", "mode");
        assertEquals("drivingdistancematrix",
                mapping.resolveUpstreamName(Map.of("mode", "driving")));
    }

    @Test
    void template_resolveUpstreamName_ridingDistance() {
        ToolMapping mapping = ToolMapping.template("mt_map_distance", "{mode}distancematrix", "mode");
        assertEquals("ridingdistancematrix",
                mapping.resolveUpstreamName(Map.of("mode", "riding")));
    }

    @Test
    void template_missingMode_returnsTemplate() {
        ToolMapping mapping = ToolMapping.template("mt_map_direction", "{mode}", "mode");
        assertEquals("{mode}", mapping.resolveUpstreamName(Map.of()));
    }

    @Test
    void template_nullParams_returnsTemplate() {
        ToolMapping mapping = ToolMapping.template("mt_map_direction", "{mode}", "mode");
        assertEquals("{mode}", mapping.resolveUpstreamName(null));
    }

    @Test
    void template_autoStripsTemplateKey() {
        ToolMapping mapping = ToolMapping.template("mt_map_direction", "{mode}", "mode");
        assertTrue(mapping.stripParams().contains("mode"),
                "Template mapping should auto-strip the template key");
    }

    // ---- record 基础测试 ----

    @Test
    void exposedName_returnsCorrect() {
        ToolMapping mapping = ToolMapping.literal("exposed_name", "upstream_name");
        assertEquals("exposed_name", mapping.exposedName());
    }

    @Test
    void nullStripParams_becomesEmptyList() {
        ToolMapping mapping = new ToolMapping("a", "b", null, null, null, null);
        assertNotNull(mapping.stripParams());
        assertTrue(mapping.stripParams().isEmpty());
    }

    @Test
    void nullInjectParams_becomesEmptyMap() {
        ToolMapping mapping = new ToolMapping("a", "b", null, null, null, null);
        assertNotNull(mapping.injectParams());
        assertTrue(mapping.injectParams().isEmpty());
    }
}
