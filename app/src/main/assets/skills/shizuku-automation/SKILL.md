---
name: shizuku-automation
description: "Use Shizuku Bridge for basic bridge status check and shell execution."
---

# Shizuku Bridge 使用说明

Shizuku Bridge 当前可用的能力仅限基础 HTTP 接口：`/shizuku/status` 与 `/shizuku/exec`。

## 适用场景

- 系统权限受限场景下执行 shell 命令
- 适配 Wireless ADB 的 shell 执行场景（通过该 bridge 下发命令）
- 检查 bridge 服务/绑定状态

## 配置

- 地址与鉴权信息读取自 `~/.openclaw/shizuku-bridge.json`
- 请求需携带 `Authorization: Bearer <token>`

## 支持能力（仅存在能力）

- `GET /shizuku/status`：查询 Bridge 当前状态
- `POST /shizuku/exec`：执行 shell 命令

## 示例

```bash
curl -sS -X GET http://127.0.0.1:18790/shizuku/status \
  -H "Authorization: Bearer <token>"
```

```bash
curl -sS -X POST http://127.0.0.1:18790/shizuku/exec \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"command":"dumpsys battery","timeoutMs":30000}'
```

## 返回值

- `/shizuku/status`：

```json
{"status":"RUNNING","serviceBound":true}
```

- `/shizuku/exec`：

```json
{"ok":true,"exitCode":0,"stdout":"...","stderr":"","mode":"shizuku","fallback":false}
```

- 通用错误：

```json
{"ok":false,"error":"...","exitCode":-1,"stdout":"","stderr":"..."}
```

## 注意事项

- 仅保留 `status` 与 `exec` 两个基础能力，不再列出应用/界面级操作命令。
- `POST /shizuku/exec` 可承载系统 shell 执行，适用于常见 Wireless ADB 方式下的命令调用。
- `timeoutMs` 可选，默认值为 `30000`。
