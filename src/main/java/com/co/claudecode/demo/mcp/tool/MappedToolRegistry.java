package com.co.claudecode.demo.mcp.tool;

import com.co.claudecode.demo.mcp.client.McpConnectionManager;
import com.co.claudecode.demo.tool.Tool;
import com.co.claudecode.demo.tool.ToolMetadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 映射工具工厂 — 创建所有名称映射的 MCP 工具。
 * <p>
 * 将上游 MCP 服务器的工具（通过 {@link McpConnectionManager} 连接）映射为
 * 对 LLM 友好的工具名，同时处理系统参数注入和模板解析。
 * <p>
 * 工具定义从 xt-claude 项目的配置文件提取：
 * <ul>
 *   <li>美团搜索（xt-search）：tools/xt-search/tool-overrides.json</li>
 *   <li>美团地图（mt-map）：tools/mt-map/mt-map-overrides.json</li>
 * </ul>
 */
public final class MappedToolRegistry {

    /** xt-search MCP 服务器名（.mcp.json 中的 key）。 */
    public static final String XT_SEARCH_SERVER = "xt-search";

    /** mt-map MCP 服务器名（.mcp.json 中的 key）。 */
    public static final String MT_MAP_SERVER = "mt-map";

    // xt-search 系统注入参数（从 xt-search-defaults.json 提取的固定值）
    private static final Map<String, Object> SEARCH_SYSTEM_PARAMS = Map.of(
            "lat", 40.007936,
            "lng", 116.486665,
            "userId", "457809295",
            "uuid", "0000000000000CDA21717A41045E8939B6BCE32CF544CA175023229083134357",
            "searchSource", 15,
            "mode", "think"
    );

    /** 只注入 lat, lng, uuid, userId, searchSource（不含 mode）的子集。 */
    private static final Map<String, Object> SEARCH_SYSTEM_PARAMS_NO_MODE;

    static {
        Map<String, Object> m = new LinkedHashMap<>(SEARCH_SYSTEM_PARAMS);
        m.remove("mode");
        SEARCH_SYSTEM_PARAMS_NO_MODE = Map.copyOf(m);
    }

    private MappedToolRegistry() {
    }

    // ================================================================
    //  公开工厂方法
    // ================================================================

    /**
     * 创建所有映射工具（美团搜索 + 美团地图）。
     */
    public static List<Tool> createAllTools(McpConnectionManager mgr) {
        List<Tool> tools = new ArrayList<>();
        tools.addAll(createXtSearchTools(mgr));
        tools.addAll(createMtMapTools(mgr));
        return tools;
    }

    /**
     * 创建美团搜索（xt-search）映射工具。
     * <p>
     * 工具名映射：
     * <ul>
     *   <li>{@code meituan_search_mix} → upstream {@code offline_meituan_search_mix}</li>
     *   <li>{@code content_search} → upstream {@code content_search_plus_v2}</li>
     *   <li>{@code id_detail_pro} → upstream {@code id_detail_pro}</li>
     *   <li>{@code meituan_search_poi} → upstream {@code offline_meituan_search_poi}</li>
     * </ul>
     */
    public static List<Tool> createXtSearchTools(McpConnectionManager mgr) {
        List<Tool> tools = new ArrayList<>();

        // 1. meituan_search_mix → offline_meituan_search_mix
        tools.add(createTool(mgr, XT_SEARCH_SERVER,
                ToolMapping.literal("meituan_search_mix", "offline_meituan_search_mix",
                        List.of("originalQuery", "type"), SEARCH_SYSTEM_PARAMS),
                "搜索美团平台上的本地生活服务信息。返回结果涵盖商家、商品、团购等维度，"
                        + "业务覆盖外卖、闪购、医药、医疗、到店餐饮、服务零售、酒店、旅行等。\n"
                        + "仅在用户需要查找美团平台上具体的本地生活服务时调用。\n"
                        + "通用知识问答、非本地生活服务类需求不应调用此工具。",
                List.of(
                        param("queries", "可同时传入多个关键词并发搜索。每条query以简短搜索词为主，"
                                + "最多附加1-2个约束条件。示例：[\"西餐厅\", \"海底捞\"]", true),
                        param("location", "目标供给所在地点名称。搜索附近时留空；"
                                + "搜索指定地点时填写精确名称，不要填经纬度。", false),
                        param("level", "搜索复杂度：0—直接搜索；1—搜索词+1-2个条件；"
                                + "2—多条件组合或主观判断", true)
                ),
                true));

        // 2. content_search → content_search_plus_v2
        tools.add(createTool(mgr, XT_SEARCH_SERVER,
                ToolMapping.literal("content_search", "content_search_plus_v2"),
                "内容搜索（网页+小红书+美团UGC）。搜索攻略、评价、经验、知识类信息。"
                        + "当需要了解背景知识、用户评价、生活经验时使用此工具。",
                List.of(
                        param("keywords", "搜索关键词列表，可填多个以扩大召回范围", true),
                        param("categoryList", "内容类别编号列表。常用：25=美食，4=出行，5=医疗健康，"
                                + "27=运动健身，0=未知", true),
                        param("requirement", "用户需求的完整描述", true)
                ),
                true));

        // 3. id_detail_pro → id_detail_pro (名称一致)
        tools.add(createTool(mgr, XT_SEARCH_SERVER,
                ToolMapping.literal("id_detail_pro", "id_detail_pro"),
                "查询商家/商品/团购的详细信息。传入搜索结果中的id（如poi_xxx、deal_xxx），"
                        + "获取完整详情（地址、营业时间、评价、价格等）。",
                List.of(
                        param("ids", "id列表，使用搜索结果中返回的id（如[\"poi_12345\"]）", true),
                        param("query", "用户的主观需求，用于精准提取相关评论和信息", true)
                ),
                true));

        // 4. meituan_search_poi → offline_meituan_search_poi
        tools.add(createTool(mgr, XT_SEARCH_SERVER,
                ToolMapping.literal("meituan_search_poi", "offline_meituan_search_poi",
                        List.of("originalQuery"), SEARCH_SYSTEM_PARAMS_NO_MODE),
                "美团店铺内搜索。在已知商家（poiId）内搜索具体商品/团购/服务。",
                List.of(
                        param("queries", "需求描述列表", true),
                        param("location", "目标地址。搜索附近时置空", false),
                        param("poiId", "门店id（以'poi_'开头）", false)
                ),
                true));

        return tools;
    }

    /**
     * 创建美团地图（mt-map）映射工具。
     * <p>
     * 工具名映射：
     * <ul>
     *   <li>{@code mt_map_geo} → upstream {@code geo}</li>
     *   <li>{@code mt_map_regeo} → upstream {@code regeo}</li>
     *   <li>{@code mt_map_text_search} → upstream {@code text}</li>
     *   <li>{@code mt_map_nearby} → upstream {@code nearby}</li>
     *   <li>{@code mt_map_direction} → upstream {@code {mode}}（模板）</li>
     *   <li>{@code mt_map_distance} → upstream {@code {mode}distancematrix}（模板）</li>
     *   <li>{@code mt_map_iplocate} → upstream {@code iplocate}</li>
     *   <li>{@code mt_map_poiprovide} → upstream {@code poiprovide}</li>
     * </ul>
     */
    public static List<Tool> createMtMapTools(McpConnectionManager mgr) {
        List<Tool> tools = new ArrayList<>();

        // 1. mt_map_geo → geo
        tools.add(createTool(mgr, MT_MAP_SERVER,
                ToolMapping.literal("mt_map_geo", "geo"),
                "美团地图-地理编码：将地址/地名转为经纬度坐标。",
                List.of(
                        param("address", "待解析的结构化地址信息", true),
                        param("city", "指定城市，可提升解析精度", false)
                ),
                true));

        // 2. mt_map_regeo → regeo
        tools.add(createTool(mgr, MT_MAP_SERVER,
                ToolMapping.literal("mt_map_regeo", "regeo"),
                "美团地图-逆地理编码：将经纬度坐标转为行政区划地址。",
                List.of(param("location", "格式：经度,纬度", true)),
                true));

        // 3. mt_map_text_search → text
        tools.add(createTool(mgr, MT_MAP_SERVER,
                ToolMapping.literal("mt_map_text_search", "text"),
                "美团地图-关键词搜索：按关键词搜索地图上的地点/POI。",
                List.of(
                        param("keywords", "查询关键字", true),
                        param("location", "搜索位置坐标（GCJ02），格式：经度,纬度", false),
                        param("city", "查询城市", false)
                ),
                true));

        // 4. mt_map_nearby → nearby
        tools.add(createTool(mgr, MT_MAP_SERVER,
                ToolMapping.literal("mt_map_nearby", "nearby"),
                "美团地图-周边搜索：以坐标为中心搜索指定半径内的地点。",
                List.of(
                        param("keywords", "搜索关键词", true),
                        param("location", "中心点坐标：经度,纬度", true),
                        param("radius", "搜索半径（米），范围0-50000，默认1000", false)
                ),
                true));

        // 5. mt_map_direction → {mode} (模板)
        tools.add(createTool(mgr, MT_MAP_SERVER,
                ToolMapping.template("mt_map_direction", "{mode}", "mode"),
                "美团地图-路径规划：支持驾车、步行、骑行、电单车、公共交通五种出行方式。",
                List.of(
                        param("mode", "出行方式：driving/walking/riding/electrobike/transit", true),
                        param("origin", "出发点经纬度，格式：经度,纬度", true),
                        param("destination", "目的地经纬度，格式：经度,纬度", true),
                        param("strategy", "路线策略（仅driving/transit有效）", false)
                ),
                true));

        // 6. mt_map_distance → {mode}distancematrix (模板)
        tools.add(createTool(mgr, MT_MAP_SERVER,
                ToolMapping.template("mt_map_distance", "{mode}distancematrix", "mode"),
                "美团地图-距离测量：计算起点到终点的距离和时间。支持驾车/步行/骑行。",
                List.of(
                        param("mode", "出行方式：driving/walking/riding", true),
                        param("origins", "起点列表，每项格式 {\"location\": \"经度,纬度\"}", true),
                        param("destinations", "终点列表，每项格式 {\"location\": \"经度,纬度\"}", true)
                ),
                true));

        // 7. mt_map_iplocate → iplocate
        tools.add(createTool(mgr, MT_MAP_SERVER,
                ToolMapping.literal("mt_map_iplocate", "iplocate"),
                "美团地图-IP定位：根据IP地址获取大致地理位置。",
                List.of(param("ip", "IP地址，支持IPv4、IPv6", true)),
                true));

        // 8. mt_map_poiprovide → poiprovide
        tools.add(createTool(mgr, MT_MAP_SERVER,
                ToolMapping.literal("mt_map_poiprovide", "poiprovide"),
                "美团地图-POI详情查询：根据POI ID查询详细信息。",
                List.of(
                        param("ids", "POI ID列表，最多50个", true),
                        param("id_type", "ID类型：mid(地图ID)、mt_bid(美团ID)、dp_bid(点评ID)", true)
                ),
                true));

        return tools;
    }

    // ================================================================
    //  内部工具方法
    // ================================================================

    private static MappedMcpTool createTool(McpConnectionManager mgr, String serverName,
                                             ToolMapping mapping, String description,
                                             List<ToolMetadata.ParamInfo> params,
                                             boolean readOnly) {
        ToolMetadata metadata = new ToolMetadata(
                mapping.exposedName(),
                description,
                readOnly,
                readOnly,
                false,
                ToolMetadata.PathDomain.NONE,
                null,
                params,
                true,           // isMcp — 映射工具也是 MCP 工具，默认延迟加载
                false,          // shouldDefer
                false,          // alwaysLoad
                ""              // searchHint
        );
        return new MappedMcpTool(serverName, mapping, metadata, mgr);
    }

    private static ToolMetadata.ParamInfo param(String name, String description, boolean required) {
        return new ToolMetadata.ParamInfo(name, description, required);
    }
}
