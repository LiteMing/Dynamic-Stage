# Dynamic Stage 仓库审查

审查日期：2026-08-12

## 结论

当前仓库已经从“DS 读取 Voxy/DH 缓存、烘焙 `.sdb`、经服务端分块传输、客户端自行建网格渲染”重构为 DH 原生渲染方案：

```text
服务端 Dynamic Stage
  -> 管理实例、成员、容量、物理 region
  -> 同步 lodPackId、lodAnchor、flight epoch
  -> 等客户端 ready 后传送

客户端 Dynamic Stage
  -> 校验本地独立 LOD 包
  -> 将关卡维度的 DH 保存目录重定向到包内 dh/
  -> 临时开启 DH read-only
  -> 向 DH 提供虚拟相机/玩家位置

Distant Horizons
  -> 打开自己的 SQLite
  -> 自己缓存、建模、调度和渲染
```

因此 DS 不再承担 LOD 烘焙、文件传输、格式转换、网格上传或渲染。服务器负担只剩实例状态、ready 握手和少量控制包；LOD 数据的分发由整合包或其他客户端文件分发机制负责。

## 仓库基线

- 当前工程：Forge 1.20.1、ForgeGradle 6、Java 17。
- 当前并非 Architectury 多平台工程；Fabric/Voxy 尚未接入。
- DH 参考源码：`D:\IdeaProjects\DistantHorizons`，`multiversion_test`。
- 实际开发运行 JAR：Distant Horizons 3.2.0-b（Minecraft 1.20.1）。
- Voxy 参考：`D:\IdeaProjects\voxy-thirdparty`，`mc_1201`；本轮未修改。
- 音乐参考：`D:\IdeaProjects\mob-battle-music`；本轮未修改，音乐仍由 MBM 负责。

## 已完成：关卡实例

- `StageSession` 持久化玩家、实例 UUID、stage ID、LOD 包 ID、LOD 锚点、region slot、容量、返回点和 flight 状态。
- 旧 `.sdb` 会话没有实例/LOD 包字段，会被明确视为不兼容状态并忽略；旧测试世界应先退出/清理旧关卡会话。
- `capacity = 1` 表示单人实例；多人可通过实例 UUID 加入同一 region。
- 不同实例使用间隔 2048 方块的独立 region，同一实例成员共享 region。
- 玩家 persistent NBT 写入 `DynamicStageInstance=<instance UUID>`，供 KubeJS/MBM 等外部编排检查。
- 客户端先校验 LOD 包，发送首个 ready 后服务端才传送；目标 wrapper 出现后再重绑外部数据库，flight 在重绑成功前暂停，重绑失败会安全退出。
- 重连时若本地包丢失或损坏，玩家会退出关卡并返回原位置。
- LOD 锚点与物理 region 解耦；更新锚点会广播给已进入和仍在 ready 握手中的成员。
- 关卡维度禁止方块左键、右键、物品对方块使用和破坏。

目前实例是“只要至少一个持久化成员存在即存活”的轻量模型，还没有战斗状态机、房主/权限、胜负条件、自动遭遇入口或实例列表 API。

## 已完成：DH 原生兼容

- LOD 包位于 `.minecraft/dynamicstage/lodpacks/<namespace>/<path>/`，可完全独立于当前存档和 DH 默认缓存目录。
- `manifest.json` 锁定格式版本、后端、MC/DH 版本和关卡高度；路径和数据库有边界、符号链接、大小及 SQLite header 校验。
- 使用 DH 公开 `IDhApiSaveStructure` 扩展点重定向 `dynamicstage:stg_stage` 的保存目录。
- override 只在 DS 会话期间绑定，退出时解除，避免长期遮蔽其他 DH 兼容模组。
- DH world API 必须已加载且成功进入 read-only，客户端才会报告 ready。
- 退出或切包时先关闭 DH level，精确失效当前 wrapper 的保存目录缓存，再重新加载；不会清空其他维度缓存。
- Mixin 覆盖 DH 3.2.0-b Forge 的：
  - `MinecraftRenderWrapper_forge#getCameraExactPosition`
  - `MinecraftClientWrapper_forge#getPlayerBlockPos`
  - `MinecraftClientWrapper_forge#getPlayerChunkPos`
- 虚拟位置只在客户端已经处于 `dynamicstage:stg_stage` 时生效，ready 握手期间不会污染来源世界 DH 视点。

开发客户端日志已确认 `dynamicstage.mixins.json (2)` 被加载，两个 Mixin 均实际注入对应 `_forge` 类，随后 DH 3.2.0-b 完成初始化、OpenGL 绑定和主菜单资源加载，无 `InvalidMixin`/注入失败。

## 已完成：客户端动画基础

- CMDCam 场景仍采用受限导入、内容寻址存储和服务端授权的 game-time epoch。
- 同一实例锁定同一个 flight hash、字节数、时长和起点；后来替换同名 stage 配置只影响新实例。
- 加入者继承已有实例的 flight，不会重新读取当前 active 配置造成成员分叉。
- 客户端通过 CMDCam 自己的插值/播放管线运行动画；DS 只读取相对位移并改变 DH 虚拟来源坐标。
- DS 不实现音乐播放。KubeJS 可同时检查 MBM marker 与 `DynamicStageInstance` 驱动客户端效果。

需要注意：DH 朝向仍读取 Minecraft/CMDCam 主相机，DS 只覆盖 DH 坐标；yaw、pitch、roll 的最终视觉必须用真实 CMDCam 路径验收。

## 已删除

- `.sdb`/Backdrop Blob 格式及所有分块网络协议。
- 服务端 DH SQLite、Voxy RocksDB 读取器和 portable baker。
- DS 自有客户端 LOD cache/downloader/voxel renderer。
- RocksDB、Zstd、SQLite JDBC、LZ4、XZ 等内嵌依赖。
- 与上述旧链路对应的探针和单元测试。

这些删除直接落实“DS 不负责 LOD 渲染和重构缓存”的目标，也移除了与目标 3 无关的未完成负担。

## 当前验证

已通过：

```powershell
.\gradlew.bat clean test jarJar
.\gradlew.bat -PincludeDh=true runClient
```

- JUnit 覆盖 flight 格式/资产、region 布局、LOD manifest、SQLite header 和符号链接路径拒绝。
- 发布 JAR 含 DS Mixin 配置和两个 DH Mixin，不含旧 LOD 数据库/压缩依赖。
- DH 3.2.0-b 客户端初始化与 Mixin 实际应用通过。

尚未完成：

- 用真实外部 `DistantHorizons.sqlite` 进入关卡并截图确认画面。
- 同一连接内切换两个 LOD 包，确认 DH 实际打开不同数据库。
- 双客户端多人容量、共同锚点和共同 flight epoch 验收。
- 断线重连、包删除、数据库被占用、集成服务器与专用服务器全流程。
- CMDCam 与当前 CreativeCore 运行依赖的兼容性；现有 Modrinth CreativeCore 在开发映射环境会先于 DS 因自身 `ShapesMixin` 失败。
- KubeJS + MBM marker/NBT 的实际脚本接口和音乐同步效果。

## 后续顺序

1. 准备真实 DH 3.2.0-b LOD 包，完成挂载、锚点、切包、退出和只读恢复的游戏内验收。
2. 完成双客户端实例/重连测试，并定义自动遭遇、完成、取消和超时状态机。
3. 选择与当前 Forge 映射兼容的 CMDCam/CreativeCore 构建，验证 XYZ/yaw/pitch/roll 和 MBM/KubeJS 同步。
4. 固化 DH 兼容版本范围和失败提示，再开始 Fabric 1.20.1 Voxy 原生渲染适配。
5. 目标 1/2 稳定后，只恢复目标 3 所需的最小边界与基础场地，不迁回旧编辑器/烘焙架构。
