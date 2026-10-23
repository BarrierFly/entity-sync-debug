# Entity Sync Debug

A client-side Fabric mod for Minecraft 1.21.11 that detects server-tracked entities which are missing on the client in singleplayer.

## What it does

- Only runs while a singleplayer integrated server is active.
- Every second, takes a snapshot of the entities the server has started tracking for the local player.
- Compares those entities by UUID against the client world, after a short grace period (2 seconds) to allow spawn packets to arrive.
- When an entity is missing, displays a red chat message and writes a detailed warning to the game log.
- Reports each entity once until it reappears, then logs the recovery.

The log entry contains the dimension, entity type and name, UUID, server entity ID, server position and chunk, client player position, horizontal/3D distance, view distance, per-type tracking range, whether the entity's chunk is loaded on the client, the server tick, and how long the entity has been missing.

## Detection semantics

The mod does not simply compare every entity within the render distance. An entity can be inside the client render distance but outside its per-type entity tracking range, in which case vanilla never sends it and it is not a sync failure. The server's own tracking set is used as the ground truth: if the server has started tracking an entity for the local player, that entity is expected to exist on the client.

A report with `client chunk loaded: false` usually points at a chunk loading/unloading edge case rather than a lost entity spawn packet.

## Commands

- `/entitysyncdebug counts` — count server-side and client-side entities by type within the current singleplayer view distance. The output shows one line per type with both counts, highlights differences, and ends with a total.

The command counts all loaded entities in range on each side, not only entities the server currently tracks. Counts can therefore differ for entity types whose tracking range is smaller than the view distance; use the automatic missing-entity report for the tracked-entity comparison.

## Requirements

- Minecraft 1.21.11
- Fabric Loader 0.19.5 or newer
- Fabric API for 1.21.11
- Java 21 or newer

## Build

```bash
./gradlew build
```

The mod jar is written to `build/libs/entity-sync-debug-1.0.0.jar`.

## Notes

- This is a debug tool and is always active in singleplayer. It does not add commands or configuration.
- It is client-side only and does nothing when connected to a remote server.

## 中文说明

在单人游戏（集成服务器）中，每秒比较一次“服务端已开始向本地玩家追踪的实体”和客户端实体表。若某个实体连续 2 秒在客户端缺失，会在聊天栏显示红色提示，并在游戏日志中输出维度、类型、UUID、实体 ID、坐标、距离、视距、实体追踪范围、客户端区块是否已加载等详细信息。

使用服务端追踪集合而不是简单按视距扫描，可以避免把“超出实体类型追踪范围、原版本来就不会同步”的实体误判为缺失。构建后 jar 位于 `build/libs/entity-sync-debug-1.0.0.jar`。

命令 `/entitysyncdebug counts` 会按实体类型列出当前视距内服务端和客户端的实体数量及差值。该命令统计的是范围内所有已加载实体，因此某些类型（追踪范围小于视距）出现数量差属于正常现象；判断同步缺失请以自动检测为准。
