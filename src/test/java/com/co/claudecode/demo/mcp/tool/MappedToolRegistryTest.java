package com.co.claudecode.demo.mcp.tool;

import com.co.claudecode.demo.mcp.client.McpConnectionManager;
import com.co.claudecode.demo.tool.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MappedToolRegistry 单元测试。
 * <p>
 * 验证工具创建数量、名称、唯一性、readOnly 属性等。
 */
class MappedToolRegistryTest {

    private McpConnectionManager mgr;

    @BeforeEach
    void setUp() {
        mgr = new McpConnectionManager();
    }

    @AfterEach
    void tearDown() {
        mgr.close();
    }

    // ---- xt-search 工具 ----

    @Test
    void xtSearchTools_count() {
        List<Tool> tools = MappedToolRegistry.createXtSearchTools(mgr);
        assertEquals(4, tools.size());
    }

    @Test
    void xtSearchTools_names() {
        List<Tool> tools = MappedToolRegistry.createXtSearchTools(mgr);
        Set<String> names = new HashSet<>();
        tools.forEach(t -> names.add(t.metadata().name()));

        assertTrue(names.contains("meituan_search_mix"));
        assertTrue(names.contains("content_search"));
        assertTrue(names.contains("id_detail_pro"));
        assertTrue(names.contains("meituan_search_poi"));
    }

    @Test
    void xtSearchTools_allReadOnly() {
        List<Tool> tools = MappedToolRegistry.createXtSearchTools(mgr);
        assertTrue(tools.stream().allMatch(t -> t.metadata().readOnly()));
    }

    @Test
    void xtSearchTools_haveDescriptions() {
        List<Tool> tools = MappedToolRegistry.createXtSearchTools(mgr);
        for (Tool tool : tools) {
            assertNotNull(tool.metadata().description());
            assertFalse(tool.metadata().description().isEmpty(),
                    tool.metadata().name() + " should have a description");
        }
    }

    @Test
    void xtSearchTools_haveParams() {
        List<Tool> tools = MappedToolRegistry.createXtSearchTools(mgr);
        for (Tool tool : tools) {
            assertFalse(tool.metadata().params().isEmpty(),
                    tool.metadata().name() + " should have parameters");
        }
    }

    // ---- mt-map 工具 ----

    @Test
    void mtMapTools_count() {
        List<Tool> tools = MappedToolRegistry.createMtMapTools(mgr);
        assertEquals(8, tools.size());
    }

    @Test
    void mtMapTools_names() {
        List<Tool> tools = MappedToolRegistry.createMtMapTools(mgr);
        Set<String> names = new HashSet<>();
        tools.forEach(t -> names.add(t.metadata().name()));

        assertTrue(names.contains("mt_map_geo"));
        assertTrue(names.contains("mt_map_regeo"));
        assertTrue(names.contains("mt_map_text_search"));
        assertTrue(names.contains("mt_map_nearby"));
        assertTrue(names.contains("mt_map_direction"));
        assertTrue(names.contains("mt_map_distance"));
        assertTrue(names.contains("mt_map_iplocate"));
        assertTrue(names.contains("mt_map_poiprovide"));
    }

    @Test
    void mtMapTools_allReadOnly() {
        List<Tool> tools = MappedToolRegistry.createMtMapTools(mgr);
        assertTrue(tools.stream().allMatch(t -> t.metadata().readOnly()));
    }

    @Test
    void mtMapTools_directionHasModeParam() {
        List<Tool> tools = MappedToolRegistry.createMtMapTools(mgr);
        Tool direction = tools.stream()
                .filter(t -> t.metadata().name().equals("mt_map_direction"))
                .findFirst().orElseThrow();

        boolean hasModeParam = direction.metadata().params().stream()
                .anyMatch(p -> p.name().equals("mode"));
        assertTrue(hasModeParam, "mt_map_direction should have a 'mode' parameter");
    }

    // ---- 全部工具 ----

    @Test
    void allTools_count() {
        List<Tool> tools = MappedToolRegistry.createAllTools(mgr);
        assertEquals(12, tools.size(), "Should have 4 search + 8 map = 12 tools");
    }

    @Test
    void allTools_uniqueNames() {
        List<Tool> tools = MappedToolRegistry.createAllTools(mgr);
        Set<String> names = new HashSet<>();
        for (Tool tool : tools) {
            assertTrue(names.add(tool.metadata().name()),
                    "Duplicate tool name: " + tool.metadata().name());
        }
    }

    @Test
    void allTools_areMappedMcpTool() {
        List<Tool> tools = MappedToolRegistry.createAllTools(mgr);
        for (Tool tool : tools) {
            assertInstanceOf(MappedMcpTool.class, tool);
        }
    }

    @Test
    void allTools_correctServerNames() {
        List<Tool> tools = MappedToolRegistry.createAllTools(mgr);
        for (Tool tool : tools) {
            MappedMcpTool mapped = (MappedMcpTool) tool;
            String name = mapped.metadata().name();
            if (name.startsWith("mt_map_")) {
                assertEquals("mt-map", mapped.serverName());
            } else {
                assertEquals("xt-search", mapped.serverName());
            }
        }
    }

    // ---- 模板工具检查 ----

    @Test
    void directionTool_isTemplate() {
        List<Tool> tools = MappedToolRegistry.createMtMapTools(mgr);
        MappedMcpTool direction = (MappedMcpTool) tools.stream()
                .filter(t -> t.metadata().name().equals("mt_map_direction"))
                .findFirst().orElseThrow();

        assertTrue(direction.mapping().isTemplate());
        assertEquals("{mode}", direction.mapping().template());
        assertEquals("mode", direction.mapping().templateKey());
    }

    @Test
    void distanceTool_isTemplate() {
        List<Tool> tools = MappedToolRegistry.createMtMapTools(mgr);
        MappedMcpTool distance = (MappedMcpTool) tools.stream()
                .filter(t -> t.metadata().name().equals("mt_map_distance"))
                .findFirst().orElseThrow();

        assertTrue(distance.mapping().isTemplate());
        assertEquals("{mode}distancematrix", distance.mapping().template());
    }

    @Test
    void geoTool_isNotTemplate() {
        List<Tool> tools = MappedToolRegistry.createMtMapTools(mgr);
        MappedMcpTool geo = (MappedMcpTool) tools.stream()
                .filter(t -> t.metadata().name().equals("mt_map_geo"))
                .findFirst().orElseThrow();

        assertFalse(geo.mapping().isTemplate());
        assertEquals("geo", geo.mapping().upstreamName());
    }

    // ---- 服务器名常量 ----

    @Test
    void serverNameConstants() {
        assertEquals("xt-search", MappedToolRegistry.XT_SEARCH_SERVER);
        assertEquals("mt-map", MappedToolRegistry.MT_MAP_SERVER);
    }
}
