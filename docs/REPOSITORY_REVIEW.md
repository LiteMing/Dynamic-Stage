# Dynamic Stage 仓库审查

审查日期：2026-08-12

## 结论

当前仓库是 Minecraft Forge 1.20.1 单模块原型，并非已经完成的 Architectury 多平台工程。它已经具备“进入空关卡维度、从 Voxy 或 Distant Horizons 读取周边 LOD、客户端生成背景网格、禁止关卡方块交互、用 CMDCam 当前播放姿态驱动背景反向变换”的最小链路。

这条链路目前只适合单人集成客户端开发。自动遭遇战、远程客户端数据分发、多玩家独立实例和可持久化关卡配置仍未完成，因此不能将目标 1 或目标 2 标记为成品。

## 平台与参考基线

- 当前工程：Forge 1.20.1，ForgeGradle 6，Java 17。
- Voxy 参考：`D:\IdeaProjects\voxy-thirdparty`，分支 `mc_1201`，审查提交 `69b19fe`。
- Distant Horizons 参考：`D:\IdeaProjects\DistantHorizons`。
- CMDCam 参考：`D:\IdeaProjects\CMDCam`，分支 `1.20`，审查提交 `f0f18cb`。
- 旧主体参考：`D:\IdeaProjects\Stage-Dimensions`。该仓库存在未提交改动，本次只读审查，没有修改。

## 目标 1：隔离遭遇战关卡

已具备：

- `dynamicstage:stg_stage` 空维度和手动进入/退出命令。
- 按锚点读取 Voxy RocksDB 或 DH SQLite 的周边 LOD。
- LOD 只在客户端建立 GPU 网格，关卡服务端不需要复制背景方块。
- 关卡维度内方块左键、右键、物品对方块使用及破坏事件被禁止。
- 离开关卡会释放背景网格；异步旧请求不能覆盖新关卡背景。

主要缺口：

- 没有自动遭遇触发和战斗结束条件。
- `/dynamicstage exit` 只返回主世界出生点，没有保存原维度、位置、朝向和状态。
- `StageSession` 是共享 JVM 静态状态，不支持多玩家独立会话或多个同时关卡实例。
- 没有死亡、掉线、重连、服务器停止时的会话恢复和清理。
- 服务端保存的绝对 LOD 文件路径无法被远程客户端直接使用。
- `.sdb` 已有格式和往返测试，但烘焙、S2C 分块分发、校验与客户端缓存尚未接线。

## 目标 2：STG 摄像机背景

已具备：

- Voxy 与 DH 数据可以转为客户端体素网格。
- Voxy 多级 LOD 使用距离环带，并按 `2^level` 还原高级 LOD 单元坐标和尺寸。
- DH 按 section 坐标和 detail level 计算方块范围，支持未压缩、LZ4 和 XZ，校验 `DataFormatVersion == 1`。
- CMDCam 反射桥读取 `CamRun.currentStage` 和当前 stage 内时间，按帧插值获取 6DoF 姿态。
- 渲染器将虚拟摄像机位姿反向应用到背景，运动逻辑只在客户端执行。

主要缺口：

- 没有将 CMDCam 路径文件作为关卡资产导入或持久化引用。
- 没有服务端授权和同步 flight/stage 播放状态；远程多人无法保证一致起点。
- 单个 `VertexBuffer` 有内存和上传峰值风险，尚未分页、分区或按视锥调度。
- 尚未用实际游戏截图或录像验证所有 yaw、pitch、roll 和位移方向。
- 没有在目标硬件上记录帧时间、显存、加载耗时和大范围 LOD 上限。

## 目标 3：边界与自定义场地

新仓库没有迁入旧项目的场地编辑器、模板和自定义场地主体，因此没有大块目标 3 实现需要删除。本次只移除了空的 Mixin、Access Transformer、服务器事件占位和 Postman 元数据。

保留 `.sdb`/Backdrop Blob 数据层是有意的：它属于目标 1 的专用服务器背景分发方案，不是自定义场地功能。未来确需边界时，应只加入最小运行时边界和基础场地生成，不迁回编辑器/UI 系统。

## 本轮修正与验证

- 修正非原点背景锚点的重复偏移。
- 修正 Voxy 多级 LOD 重叠、预算和高级 LOD 尺度。
- 移除 `VoxyProvider` 的重复二次降采样。
- 修正 DH 坐标、逐行 mapping、压缩模式、格式版本与多级距离环带。
- 修正 CMDCam 帧插值入口和 stage 内时间来源。
- 增加关卡方块交互隔离、客户端资源释放和异步加载请求隔离。
- 发布包通过 Jar-in-Jar 内嵌 RocksDB、Zstd、SQLite JDBC、LZ4 和 XZ。
- 自动测试覆盖 Backdrop Blob 往返、Voxy 有符号 section key 和高级 LOD 缩放。

## 推荐实现顺序

1. 打通 `.sdb` 烘焙、服务端分块传输、哈希校验和客户端磁盘缓存，消除远程客户端访问服务端绝对路径的问题。
2. 将 `StageSession` 改为 per-player 服务端会话，保存返回位置，并实现进入、完成、退出、死亡、掉线和重连生命周期。
3. 定义自动遭遇触发接口和关卡实例分配策略，再接战斗状态机。
4. 将 CMDCam 路径变成可部署的关卡资产，建立服务器授权播放时钟和客户端插值。
5. 对背景网格分页并完成实际运动方向、加载时间、帧时间和显存验收。
6. 目标 1/2 稳定后，再实现最小边界和基础方块场地。
