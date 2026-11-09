# MikuProtect

作者：JunXieX　|　MikuMC系列插件交流群：1105054380　|　非开源项目，请勿二次分发

MikuProtect 为 Paper / Folia 26.3 服务器提供方块、容器、实体与玩家行为记录，支持查询、回滚、还原、预览与撤销。默认使用 DuckDB 单文件存储，可切换 MariaDB；所有世界修改按区块分组并受 tick 预算限制，红石机器与方块状态按记录时的完整状态恢复。

## 功能特性

- 方块：放置、破坏、爆炸、燃烧、消退、形成、蔓延、活塞、桶、液体流动、生长、凋零、施肥、结构生长、发射器
- 容器：点击、拖拽、漏斗搬运、开箱，逐槽记录前后物品
- 实体：生成、死亡、爆炸、转化、放置、剪毛、玩家击杀与死亡；击杀实体保存完整字节并可恢复，生成记录类型/UUID
- 玩家：加入、退出、聊天、命令、告示牌修改、改名历史、丢弃/拾取/合成/损坏/食用
- 查询：玩家、动作、材质、实体、世界、半径、时间窗、关键词、分页
- 回滚 / 还原 / 预览 / 确认 / 取消 / 撤销
- 红石状态：完整方块状态（朝向、供电、延时、锁定等）+ 容器内容 + 常见方块实体数据
- Paper 与 Folia 双端，区块级区域调度
- WorldEdit / FastAsyncWorldEdit 批量编辑记录（FAWE 2.16.2+，与 WorldEdit 二选一安装；自动加入 FAWE extent 白名单，单次编辑可限流）
- 自动清理、矿物告警、公开 API 与可取消的 PreLog 事件
- 可选只读 Web API（Bearer API Key，默认关闭）

## 环境要求

| 项目 | 要求 |
| --- | --- |
| Minecraft | 26.3 |
| 服务端 | Paper 26.3 或 Folia 26.3 |
| Java | 25 |
| 存储 | DuckDB（内置）或 MariaDB 10.6+ |
| 内存 | 建议预留 512MB 以上给 DuckDB |

## 安装

1. 将 `MikuProtect-1.1.1.jar` 放入服务端 `plugins/`。
2. 启动服务器，首次会生成 `plugins/MikuProtect/config.yml` 与 `plugins/MikuProtect/data/`；loader 会联网下载 DuckDB/MariaDB/HikariCP，首次启动需要可访问 Maven Central 镜像。
3. 默认使用 DuckDB，无需额外安装数据库。
4. 如需 MariaDB，先创建数据库（例如 `CREATE DATABASE mikuprotect`），再修改 `storage.type: mariadb` 并填写连接信息，重启服务器。
5. 修改配置后执行 `/mp reload`；存储类型变更必须重启。

## 配置说明

| 配置段 | 说明 |
| --- | --- |
| `performance` | 按服务器规模选择 `auto` / `small` / `medium` / `large` / `manual`；自动覆盖队列、批量、线程和内存默认值 |
| `storage` | 存储类型、DuckDB 文件与内存限制、MariaDB 连接与连接池 |
| `recording` | 队列容量、批大小、刷盘间隔、WAL、容器聚合窗口、世界白名单、各类事件开关 |
| `lookup` | 默认/最大半径、默认时间窗、每页条数、查询节流 |
| `rollback` | 最大行数、每 tick 预算、区块预取、物理开关、红石唤醒 |
| `purge` | 自动清理开关、保留天数、删除批大小 |
| `web` | 只读 HTTP API 开关、监听地址、端口、API Key、最大返回条数、速率限制 |
| `alerts` | 矿物告警开关、告警材质、接收权限 |

默认开启全部保护项，包括活塞、漏斗、发射器、液体、爆炸与容器事务；红石机器相关项不要随意关闭。

### 性能档位

`performance.profile` 默认 `auto`，按服务端 `max-players` 选择：

| 档位 | max-players | 队列 | 批量 | 刷盘 | 容器聚合 | DuckDB 内存 | DuckDB 线程 |
|---|---:|---:|---:|---:|---:|---:|---:|
| small | ≤20 | 20,000 | 500 | 750ms | 1.5s | 256MB | 2 |
| medium | ≤100 | 50,000 | 1,000 | 500ms | 1s | 512MB | 3 |
| large | >100 | 100,000 | 2,000 | 250ms | 0.5s | 1GB | 4 |

选择 `auto` 或固定档位时，会覆盖对应的 `recording.*`、`storage.duckdb.*`、`storage.mariadb.pool-size`、`rollback.*` 和 `worldedit-max-blocks` 数值；需要完全手动调参时设为 `manual`。旧配置没有 `performance` 段时按 `manual` 处理，不会在升级后改变现有参数。

修改档位、队列/批量/刷盘、DuckDB 线程/内存或存储类型后需要重启服务器；`/mp reload` 只更新过滤、查询、回滚策略等服务配置。

### 性能对比 CI

仓库包含独立的性能对比工作流 `.github/workflows/performance.yml`，使用固定 Paper、TNT 工作负载对 MikuProtect、CoreProtect 24.2-dev（26.3 自动构建）和 Prism 做同机对比。手动触发后可在 Actions 的 Job Summary 查看 tick/TPS/存储占用表，并下载完整日志和 JSON 结果；详细说明见 `benchmark/README.md`。

## 命令

| 命令 | 说明 |
| --- | --- |
| `/mp help` | 查看帮助 |
| `/mp lookup <条件>` | 查询记录，例如 `/mp lookup u:Steve a:block-break r:10 t:1h` |
| `/mp near [半径]` | 查询附近记录 |
| `/mp rollback <条件>` | 回滚 |
| `/mp restore <条件>` | 还原 |
| `/mp preview <条件>` | 预览，不写世界 |
| `/mp apply` | 确认预览 |
| `/mp cancel` | 取消预览 |
| `/mp undo` | 撤销上一次回滚/还原 |
| `/mp purge <时间>` | 清理早于指定时间的记录 |
| `/mp status` | 查看队列、存储与版本状态 |
| `/mp flush` | 立即把队列写入存储 |
| `/mp inspect` | 开关检查器，右键方块查看最近记录 |
| `/mp reload` | 重载配置 |
| `/mp teleport <世界> <x> <y> <z>` | 传送到记录坐标 |

条件语法：`u:玩家`、`a:动作`、`a!:排除动作`、`b:材质`、`e:实体`、`w:世界`、`r:半径`、`t:时间`、`before:时间`、`p:页码`、`msg:关键词`、`#global`。

## 权限

| 节点 | 说明 | 默认 |
| --- | --- | --- |
| `mikuprotect.command` | 使用 /mp | OP |
| `mikuprotect.lookup` | 查询与 near | OP |
| `mikuprotect.rollback` | 回滚 | OP |
| `mikuprotect.restore` | 还原 | OP |
| `mikuprotect.preview` | 预览/确认/取消 | OP |
| `mikuprotect.undo` | 撤销 | OP |
| `mikuprotect.purge` | 清理 | OP |
| `mikuprotect.status` | 状态 | OP |
| `mikuprotect.reload` | 重载 | OP |
| `mikuprotect.teleport` | 传送 | OP |
| `mikuprotect.alerts` | 接收矿物告警 | OP |
| `mikuprotect.inspect` | 检查器 | OP |

## 数据与恢复

- DuckDB 数据在 `plugins/MikuProtect/data/mikuprotect.duckdb`；备份时请先停服或执行 `/mp flush`。
- 每条方块记录保存变更前后完整 `BlockData`，容器与方块实体另存二进制载荷。
- 回滚默认关闭物理更新（`rollback.apply-physics: false`），避免连锁反应覆盖已恢复的红石状态。
- 红石机器若依赖方块计划 tick，恢复后可能保持静止；可开启 `rollback.redstone-refresh` 或手动触发一次拉杆/红石更新。
- 回滚按区块提交，每个区块只处理 tick 预算内的变更，超出部分自动顺延。

## 常见问题

**Q：回滚后漏斗里的物品不对？**  
A：确认 `recording.log-container-transactions` 与 `log-hopper-transactions` 为 true；容器记录按槽位保存前后物品。

**Q：回滚后机器不动？**  
A：方块状态已恢复，但方块计划 tick 不在日志范围内。开启 `rollback.redstone-refresh` 或手动触发一次更新。

**Q：Folia 上会报区域线程错误吗？**  
A：回滚按区块调度到所属区域线程，写入队列为并发安全队列；查询与存储在工作线程完成。

**Q：可以多服共用一个 MariaDB 吗？**  
A：当前版本按单服设计，主键由进程内分配，多服共库可能冲突。
