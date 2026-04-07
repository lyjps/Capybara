---
name: 预约预定
version: 1.0.0
description: |
  帮用户搜索支持在线预订的商家（餐厅、KTV、棋牌室等）并完成预约流程。
  触发场景：用户想订位、预约、订餐厅、订KTV、预定包间等。
---

# 预约预定

## 涉及工具

1. `search_bookable_merchants` — 搜索可预订商家
2. `reserve_tool` — 执行预约（收集信息 → 确认 → 修改/取消）

---

## 工具 1：`search_bookable_merchants` — 搜索可预订商家

### 调用格式

```bash
curl -s -X POST http://localhost:3456/tool \
  -H "Content-Type: application/json" \
  -d '{
    "tool": "search_bookable_merchants",
    "params": {
      "queries": ["望京附近能订位的日料店"],
      "type": "餐厅",
      "dateBegin": "2026-03-25",
      "timeBegin": "18:00",
      "number": 4
    }
  }'
```

### 参数说明

| 参数 | 必填 | 说明 |
|------|------|------|
| `queries` | ✅ | 需求描述列表，如 `["望京能订位的日料"]` |
| `type` | ✅ | `"餐厅"` / `"KTV"` / `"棋牌室"` |
| `dateBegin` | ✅ | 预订日期，格式 `"YYYY-MM-DD"` |
| `timeBegin` | ✅ | 预订开始时间，格式 `"HH:MM"` |
| `location` | ❌ | 地区名称（如"望京"），附近时不填 |
| `dateEnd` | ❌ | 结束日期，不填默认同 dateBegin |
| `timeEnd` | ❌ | 结束时间 |
| `number` | ❌ | 用餐/到店人数 |

---

## 工具 2：`reserve_tool` — 预约操作

### 操作类型（`taskType`）

| taskType | 说明 |
|----------|------|
| `"收集预约信息"` | 开始预约流程，收集或修改预约信息 |
| `"确认预约"` | 确认并创建预约 |
| `"取消预约"` | 取消已有预约 |

### 调用格式

```bash
curl -s -X POST http://localhost:3456/tool \
  -H "Content-Type: application/json" \
  -d '{
    "tool": "reserve_tool",
    "params": {
      "taskType": "收集预约信息",
      "shopId": "poi_id_xxx",
      "productId": "deal_id_xxx"
    }
  }'
```

### 参数说明

| 参数 | 场景 | 说明 |
|------|------|------|
| `taskType` | ✅ 所有 | 操作类型（见上表） |
| `shopId` | 新预约 | 门店id（`poi_id`开头），从搜索结果获取 |
| `productId` | 新预约 | 商品/团购id（`deal_id`开头），从搜索结果获取 |
| `orderId` | 修改/取消 | 订单id（`order_id`开头） |
| `bookId` | 修改/取消 | 预约id（`book_id`开头） |

---

## 完整预约流程

```
1. 用户表达预订意图
   ↓
2. search_bookable_merchants → 获取可预订商家列表
   ↓
3. 用户选择商家
   ↓
4. reserve_tool(taskType="收集预约信息", shopId, productId)
   → 收集日期、时间、人数、联系方式等
   ↓
5. 向用户确认预约信息
   ↓
6. reserve_tool(taskType="确认预约")
   → 预约成功，返回预约ID
   ↓
7. 告知用户预约结果和注意事项
```

## 修改/取消流程

```
reserve_tool(taskType="收集预约信息", orderId 或 bookId)  → 修改
reserve_tool(taskType="取消预约", orderId 或 bookId)      → 取消
```

## 注意事项

- `shopId` 和 `productId` 必须从搜索结果中获取，不可自行填写
- 修改和取消时使用 `orderId` 或 `bookId`，从用户历史预约中获取
- 预约前确认用户的日期、时间、人数完整
