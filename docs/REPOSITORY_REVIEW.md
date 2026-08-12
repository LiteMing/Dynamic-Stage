# Dynamic Stage 仓库审查

审查日期：2026-08-12

## 结论

当前仓库是 Minecraft Forge 1.20.1 单模块原型，并非已经完成的 Architectury 多平台工程。它已经具备“进入空关卡维度、从 Voxy 或 Distant Horizons 读取周边 LOD、客户端生成背景网格、禁止关卡方块交互、用 CMDCam 当前播放姿态驱动背景反向变换”的最小链路。

这条链路已经补上远程背景分发、per-player 持久化会话、隔离 region 和可部署的 CMDCam flight 资产，但自动遭遇战入口、战斗完成条件和实际多人/视觉验收仍未完成，因此目标 1/2 仍是可运行基础设施而非成品。

## 平台与参考基线

- 当前工程：Forge 1.20.1，ForgeGradle 6，Java 17。
- Voxy 参考：`D:\IdeaProjects\voxy-thirdparty`，分支 `mc_1201`，审查提交 `69b19fe`。
- Distant Horizons 参考：`D:\IdeaProjects\DistantHorizons`。
- CMDCam 参考：`D:\IdeaProjects\CMDCam`，分支 `1.20`，审查提交 `f0f18cb`。
- 旧主体参考：`D:\IdeaProjects\Stage-Dimensions`。该仓库存在未提交改动，本次只读审查，没有修改。

## 目标 1：隔离遭遇战关卡

已具备：

- `dynamicstage:stg_stage` 空维度和手动进入/退出命令。
- 按锚点读取 Voxy RocksDB 或 DH SQLite 的周边 LOD，并在后台生成内容寻址 `.sdb`。
- Voxy 按当前维度与 biome seed 的官方目录哈希精确定位；DH 按原版维度存档目录精确定位。
- LOD 只在客户端建立 GPU 网格，关卡服务端不需要复制背景方块。
- 关卡维度内方块左键、右键、物品对方块使用及破坏事件被禁止。
- 离开关卡会释放背景网格；异步旧请求不能覆盖新关卡背景。
- `.sdb` 通过 16 KiB S2C 分块按需传输，客户端校验 SHA-256、Blob CRC 并支持断点缓存。
- 服务端使用持久化 per-player 会话，分配间隔 2048 方块的独立 region。
- `/dynamicstage exit` 精确恢复原维度、位置和朝向；登录、手动切维度、重生有清理/恢复路径。

主要缺口：

- 没有自动遭遇触发和战斗结束条件。
- 目前是一人一个 region，没有组队共享实例、容量策略或战斗状态机。
- 掉线会保留会话并在重连后恢复背景，但尚未做完整的服务端崩溃/产品丢失实机演练。
- LOD 准备是单后台 worker 且会合并同产品请求，但尚未记录大世界烘焙耗时与服务端内存峰值。

## 目标 2：STG 摄像机背景

已具备：

- Voxy 与 DH 数据可以转为客户端体素网格。
- Voxy 多级 LOD 使用距离环带，并按 `2^level` 还原高级 LOD 单元坐标和尺寸。
- DH 按 section 坐标和 detail level 计算方块范围，支持未压缩、LZ4 和 XZ，校验 `DataFormatVersion == 1`。
- CMDCam 反射桥读取 `CamRun.currentStage` 和当前 stage 内时间，按帧插值获取 6DoF 姿态。
- 渲染器将虚拟摄像机位姿反向应用到背景，运动逻辑只在客户端执行。
- 管理员可从世界存档内固定收件目录导入 CMDCam 原生场景数组，并显式选择 1-10 的场景槽位。
- 导入会限制 256 KiB、JSON 深度/节点/字符串、2-4096 路径点、时长、模式、循环和跟随目标，并按 SHA-256 持久化引用。
- 客户端完成背景校验与 GPU 上传后才发送 ready；服务端授权未来开始 tick，并在重连时用关卡维度游戏时钟恢复对应路径时间。
- flight 场景本身通过 S2C 下发，客户端复用 CMDCam 的 `CamScene` 反序列化、插值和播放实现，服务端不移动方块或摄像机。

主要缺口：

- 每个玩家会话有服务端授权时钟，但组队共享实例和跨玩家共同起点尚未实现。
- CMDCam 追帧依赖 1.20 分支 `RealTimeTimer` 私有字段的反射软适配；升级 CMDCam 时必须重新验收，失配时会跳过 flight 而不使服务端崩溃。
- 单个 `VertexBuffer` 有内存和上传峰值风险，尚未分页、分区或按视锥调度。
- 尚未用实际游戏截图或录像验证所有 yaw、pitch、roll 和位移方向。
- 没有在目标硬件上记录帧时间、显存、加载耗时和大范围 LOD 上限。

## 目标 3：边界与自定义场地

新仓库没有迁入旧项目的场地编辑器、模板和自定义场地主体，因此没有大块目标 3 实现需要删除。本次只移除了空的 Mixin、Access Transformer、服务器事件占位和 Postman 元数据。

保留 `.sdb`/Backdrop Blob 数据层是有意的：它属于目标 1 的专用服务器背景分发方案，不是自定义场地功能。未来确需边界时，应只加入最小运行时边界和基础场地生成，不迁回编辑器/UI 系统。

## 本轮修正与验证

- 修正非原点背景锚点的重复偏移。
- 修正 Voxy 多级 LOD 重叠、预算和高级 LOD 尺度。
- 删除已无调用的旧 `VoxyProvider/LodProvider` 烘焙分支，统一走 portable baker。
- 修正 DH 坐标、逐行 mapping、压缩模式、格式版本与多级距离环带。
- 修正 CMDCam 帧插值入口和 stage 内时间来源。
- 增加 CMDCam flight 的受限导入、内容寻址存储、背景 ready 握手、服务端播放 epoch 与重连追帧。
- 增加关卡方块交互隔离、客户端资源释放和异步加载请求隔离。
- 发布包通过 Jar-in-Jar 内嵌 RocksDB、Zstd、SQLite JDBC、LZ4 和 XZ。
- 自动测试覆盖 Backdrop Blob 往返/结构拒绝、内容寻址与损坏缓存拒绝、portable baker 合并、源维度指纹、Voxy 有符号 section key/高级 LOD 缩放和 region 布局。
- 真实 DH 数据库探针覆盖 62 个 section、三档 detail level、XZ 解压与 mapping/column 解析。

## 推荐实现顺序

1. 用 `runClient`/双客户端实测远程 Blob 下载、缓存命中、断线重连、原位返回和两个隔离 region。
2. 定义自动遭遇触发接口、战斗开始/完成/取消状态机及共享实例策略。
3. 用实际路径录制验证 CMDCam XYZ、yaw、pitch、roll 方向以及断线重连的中途恢复位置。
4. 对背景网格分页并记录实际运动方向、烘焙/下载耗时、帧时间、显存和服务端内存峰值。
5. 将平台无关协议/格式抽到 common 后再迁移 Architectury/Fabric；当前不能声称 Fabric/Voxy 平台完成。
6. 目标 1/2 稳定后，再实现最小边界和基础方块场地。
