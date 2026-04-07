---
name: 手艺人查询
version: 1.0.0
description: |
  搜索美发师、美甲师、纹绣师、摄影师、舞蹈教练等手艺人及其作品案例。
  触发场景：用户想找发型师、美甲、纹眉、写真摄影、舞蹈课等服务零售类需求。
---

# 手艺人查询

## 工具：`technician_work_search`

## 调用格式

```bash
curl -s -X POST http://localhost:3456/tool \
  -H "Content-Type: application/json" \
  -d '{
    "tool": "technician_work_search",
    "params": {
      "location": "<地区名称，附近时留空>",
      "search_type": "<搜索类型>",
      "gender": "<性别偏好>",
      "intent": "<服务意图>",
      "queries": ["<搜索词1>", "<搜索词2>"]
    }
  }'
```

## 参数说明

### `intent` — 服务类型（必填）

| 值 | 对应服务 |
|----|---------|
| `"0"` | 发型 / 美发 |
| `"1"` | 美甲 |
| `"2"` | 纹绣（纹眉 / 纹唇） |
| `"3"` | 摄影 / 写真 |
| `"4"` | 舞蹈 |

### `gender` — 目标性别偏好（必填）

| 值 | 含义 |
|----|------|
| `"0"` | 女性顾客 |
| `"1"` | 男性顾客 |
| `"2"` | 不限 |

### `search_type` — 搜索范围（必填）

| 值 | 含义 |
|----|------|
| `"0"` | 手艺人 + 作品都搜（不确定时用此项） |
| `"1"` | 仅搜手艺人 |
| `"2"` | 仅搜作品案例 |

### `location` — 地区（可选）

- 搜附近时**留空**
- 否则填地点/地区精确名称（如"望京"、"三里屯"），**不要填经纬度**

### `queries` — 搜索词列表（必填）

- 不含地址信息，只描述服务/风格
- 多个搜索词会触发独立多次搜索
- 示例：`["韩系短发", "空气感刘海"]`、`["猫眼美甲", "法式美甲"]`

## 典型示例

### 找附近发型师

```bash
curl -s -X POST http://localhost:3456/tool \
  -H "Content-Type: application/json" \
  -d '{
    "tool": "technician_work_search",
    "params": {
      "location": "",
      "search_type": "0",
      "gender": "0",
      "intent": "0",
      "queries": ["韩系短发", "锁骨发"]
    }
  }'
```

### 找三里屯美甲师

```bash
curl -s -X POST http://localhost:3456/tool \
  -H "Content-Type: application/json" \
  -d '{
    "tool": "technician_work_search",
    "params": {
      "location": "三里屯",
      "search_type": "1",
      "gender": "0",
      "intent": "1",
      "queries": ["法式美甲"]
    }
  }'
```

## 注意事项

- `queries` 中**不要包含地址**，地址通过 `location` 传递
- 如用户没有明确性别偏好，`gender` 填 `"2"`（不限）
- 如不确定搜手艺人还是作品，`search_type` 填 `"0"`（都搜）
