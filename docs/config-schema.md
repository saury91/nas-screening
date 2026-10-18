# 配置包格式与版本兼容

设置 → **配置备份** 里可以导出 / 导入。导出的是一个纯 JSON 文件，不含任何音视频内容，
也不含密码、Cookie 之类的凭据。仓库里 `data/` 被 `.gitignore` 排除，推上开源平台只有代码。

## 文件长什么样

```json
{
  "kind": "projection-room.config",
  "schema": 1,
  "app": "3.0",
  "exportedAt": 1792302969,
  "sections": {
    "sources": [
      { "kind": "cms", "name": "暴风资源", "url": "https://.../vod/",
        "enabled": 1, "pri": 1, "type": 1, "cat": null, "meta": {"type": 1} }
    ],
    "liveChannels": [
      { "grp": "📺央视频道", "name": "CCTV-1", "url": "http://.../cctv1.m3u8", "src": "自动汇总" }
    ],
    "prefs": { "live.seeds": ["https://.../result.m3u"] }
  },
  "counts": { "sources": 24, "liveChannels": 547 }
}
```

- `sources` 的天然主键是 `(kind, name)`，`liveChannels` 的是 `(name, url)`，导入按这两个键判断新增还是更新。
- `prefs` 只导出白名单（`live.seeds`、`music.*`、`ui.*`、`play.*`、`subtitle.*`），
  并且排除 `*.result` / `*.last`（运行结果）与含 `pass/secret/token/key/auth` 的键。
- `?channels=0` 导出时不带 `liveChannels`，适合分享（体积小很多）。

## 兼容规则

导入时按这个顺序处理（见 `server/services/config_io.py`）：

1. **比自己新 → 拒绝**，返回 409 并说明版本号，避免老程序把新字段读坏。
2. **比自己旧 → 逐级迁移**：`MIGRATIONS = {1: v1_to_v2, 2: v2_to_v3, ...}`，一段一段升上来。
3. **缺字段 → 补默认值**；**多出来的字段 → 忽略**（老程序读新文件不会炸）。
4. **单条坏数据 → 丢掉这一条并记 warning**，其余照常导入，不整体失败。
5. 也接受更早的写法：没有 `kind`、没有 `schema`、甚至只有顶层 `sources` 数组，都会尽力解析。

## 以后要改结构怎么办

假设 v2 要把 `sources[].meta.type` 挪到 `sources[].type`：

```python
# server/services/config_io.py

def _v1_to_v2(doc):
    for s in (doc.get("sections") or {}).get("sources") or []:
        if isinstance(s, dict) and s.get("type") is None:
            s["type"] = (s.get("meta") or {}).get("type") or 1
    return doc

MIGRATIONS = {1: _v1_to_v2}
SCHEMA = 2          # 别忘了把当前版本 +1
```

已有的 v1 文件导进来会自动升级；v2 文件在旧版本程序里会被明确拒绝而不是写坏数据。
`tests/test_core.py::TestConfigIO` 里有对应的回归用例。

## 安全网

- 导入前会把**当前**配置自动导出到 `data/backups/config-<时间>.json`；导错了再把这个文件导入回去即可。
- 写库在单个 SQLite 事务里，中途失败整体回滚。
- 导入面板会先 dry-run 出「新增 / 更新 / 不变」的数量和被跳过的条目，确认后才真正写入。
