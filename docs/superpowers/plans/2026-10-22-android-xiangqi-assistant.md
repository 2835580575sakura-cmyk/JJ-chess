# Android Xiangqi Assistant Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付供 iQOO Neo9S Pro+ 使用的离线安卓 APK，从 JJ 象棋人机画面识别局面，调用 Pikafish 并显示有效的走法建议。

**Architecture:** Kotlin 应用采用纯 JVM 的对局核心和图像识别模块，以及安卓引擎库和应用模块。MediaProjection 前台服务产生新画面，对局协调器只接受稳定、合法的局面；独立 `:engine` 进程通过 JNI 分析，并以局面版本过滤结果。

**Tech Stack:** JDK 21（Java/Kotlin 字节码目标 17）、Gradle 8.13、AGP 8.11.1、Kotlin 2.2.21、API 36、NDK 28.2.13676358、CMake 3.22.1、Pikafish-2026-09-06、Kotlin Coroutines 1.10.2、Kotlin Serialization 1.9.0、JUnit 4.13.2、Robolectric 4.15.1。

**Spec:** [已确认设计](../specs/2026-10-22-android-xiangqi-assistant-design.md)。本计划待用户审阅并选择执行方式；尚未开始产品实现。

## Global Constraints

- 目标设备：iQOO Neo9S Pro+；首版支持录像所示 JJ 棋盘主题、竖屏布局及双方朝向。
- 目标构建范围为 Android 10/API 29 及以上、`arm64-v8a`；使用 API 36 编译和目标平台、JDK 21、NDK 28.2.13676358。
- 用户在安卓手机的 JJ 象棋人机模式中自行落子；应用不请求无障碍权限，不替用户点击落子。
- 使用官方 Pikafish 源码和匹配的 NNUE，通过 APK 内的 JNI 库运行；安装后分析不依赖联网。
- 提供 ARMv8 基线库和 dot-product 优化库，运行时检测 CPU 能力后只装载一种；原生库满足 Android 16 KB 内存页兼容要求。
- 初始默认值为 4 个搜索线程、256 MiB Hash、`MultiPV=1`、单步 15 秒；界面提供 3、15、30 秒档位。
- 内存不足时停止搜索并降低到 2 线程、128 MiB Hash 后提示用户重试；严重热状态自动暂停搜索并清除箭头。
- 初始采样目标为每秒 3 帧；初始稳定标准为至少 3 个连续采样帧产生相同的完整格点分类，并且棋子内部区域没有显著运动或遮挡。
- 合法走法由 JNI 暴露的同版本 Pikafish 规则接口生成；FEN 内的红黑身份、当前行棋方和屏幕上下方向是三个独立字段。
- 新局面到来后立即隐藏旧箭头，再取消旧搜索；必须等旧搜索实际结束，才能设置新局面、修改线程/Hash 或开始下一次搜索。
- 默认不保存整屏录像、不上传屏幕或棋谱；原始用户录屏不提交到仓库。应用/JNI/引擎集成按 GPLv3 提供对应源码，NNUE 许可独立记录。
- 每个 APK 对应固定的源码和权重版本；只有完成真机流程后才宣称 JJ 实时辅助在目标设备上验证通过。
- 当前任务已在独立云环境中；使用现有 `/workspace/JJ-chess`，不创建额外 Git worktree。

## Review Focus

1. 手工校准点越界、捕获内容留黑边或尺寸变化：拒绝无效标定，坐标重建前不显示箭头。归属任务 1、7。
2. 手工修正得到损坏 FEN、缺少将帅或非法历史：返回明确错误，不能把未检查输入直接交给原生引擎。归属任务 1、2、8。
3. 双击开始、取消授权、先撤销录屏后关闭窗口：同一会话只创建一次捕获，重复停止安全且完整释放。归属任务 6、8。
4. 权重复制中断、校验失败或磁盘空间不足：不使用半文件，不循环崩溃，提供可重试状态。归属任务 2、9。
5. 应用恢复时 JJ 已开始另一局，或相同棋盘对应多个历史节点：不套用旧历史和旧行棋方，重新确认。归属任务 5、8、9。

## 文件结构与接口约定

四个 Gradle 模块：`:core`（棋盘、几何、历史、事件）、`:vision`（纯 Kotlin 图像算法）、`:engine`（Android Binder/JNI）、`:app`（Activity、捕获、协调、悬浮显示）。模块依赖为 `vision → core`、`engine → core`、`app → core/vision/engine`，不形成环。

- 根目录：`settings.gradle.kts`、`build.gradle.kts`、`gradle/libs.versions.toml`、Gradle wrapper、`.gitignore`。
- 构建工具：`tools/bootstrap.sh`、`tools/dev.sh`、`tools/fetch-engine.py`、`tools/prepare-fixtures.py`、`tools/verify-apk.sh`。
- 核心：`core/src/main/kotlin/dev/sakura/xiangqi/core/` 下的 `Model.kt`、`FenCodec.kt`、`Geometry.kt`、`EngineContract.kt`、`Tracking.kt`、`MoveNotation.kt`。
- 识别：`vision/src/main/kotlin/dev/sakura/xiangqi/vision/` 下的 `PixelFrame.kt`、`GridLocator.kt`、`TemplateClassifier.kt`、`BoardRecognizer.kt`、`StabilityGate.kt`。
- 引擎：`engine/src/main/kotlin/dev/sakura/xiangqi/engine/` 下的 `NativeEngine.kt`、`EngineActor.kt`、`EngineService.kt`、`BinderEngineGateway.kt`、`NetworkInstaller.kt`；`engine/src/main/cpp/` 保存原生桥接和 CMake 文件。
- AIDL：`engine/src/main/aidl/dev/sakura/xiangqi/engine/IEngineService.aidl`、`IEngineCallback.aidl`。
- 安卓应用：`app/src/main/kotlin/dev/sakura/xiangqi/app/` 下按任务列出的界面和服务文件；`app/src/main/AndroidManifest.xml`。
- 冻结依赖：`third_party/pikafish/`（源码快照，不含嵌套 `.git`）、`third_party/pikafish-manifest.json`、`third_party/licenses/`。大权重不提交，固定来源与校验值后由构建脚本准备。
- 样本：`vision/src/test/resources/jj/` 保存棋盘裁剪、人工格点标签和时间段标注；`vision/src/main/resources/jj/` 保存来自开局的模板。
- 交付：`README.md`、`docs/build.md`、`docs/device-validation.md`、`docs/validation-results.md`、`LICENSE`、`THIRD_PARTY_NOTICES.md`。

统一包名前缀 `dev.sakura.xiangqi`；应用 ID `dev.sakura.xiangqiassistant`。`RectF`、`PointF` 在核心中使用自己的不可变 `FloatRect`、`FloatPoint`，不依赖安卓类。

数据和坐标约定：`Side.RED/BLACK`；`Square(file: Int, rank: Int)` 范围分别为 0..8、0..9；a0 是红方左下角，FEN 从 rank 9 写到 rank 0；屏幕朝向保存在 `Orientation.RED_BOTTOM/BLACK_BOTTOM`。

公共契约在任务 1 定义，后续任务不得自行换名：

```kotlin
data class PositionHistory(val initialFen: String, val moves: List<UciMove>, val historyComplete: Boolean)
data class AnalysisKey(val sessionId: String, val positionRevision: Long, val searchId: Long, val transformRevision: Long)
data class EngineOptions(val threads: Int = 4, val hashMiB: Int = 256, val multiPv: Int = 1)
data class AnalysisRequest(val key: AnalysisKey, val history: PositionHistory, val movetimeMs: Int)
interface RulesGateway { suspend fun validate(history: PositionHistory): Result<LegalPosition> }
interface EngineGateway : RulesGateway {
    fun analyze(request: AnalysisRequest): Flow<EngineEvent>
    suspend fun cancel(searchId: Long)
    suspend fun close()
}
```

`Model.kt` 定义 `Piece(side, kind)`、`Board(cells)`、`UciMove(from, to)`、`PositionSnapshot(board, sideToMove)`；`Board.cells` 固定 90 项，索引 `rank*9+file`。`EngineContract.kt` 定义 `LegalPosition(snapshot, legalMoves)`、`EngineEvent.Info/BestMove/Stopped/Failure`，事件携带完整 `AnalysisKey`，评分区分厘兵和将杀，不用一个整数混淆。

`BoardObservation` 包含 `Board?`、90项置信值、时间戳、运动分数、遮挡标记；未完整识别时 `board=null`。`ConfirmedPosition` 包含 `PositionSnapshot`、`PositionHistory` 和 `positionRevision`。`GridCalibration` 分别保存捕获画面及显示屏幕中的棋盘交点矩形、`Orientation`、`transformRevision`。

`Model.kt` 的 `START_FEN` 固定为 `rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1`。测试内的 fake/recording 对象只作可控依赖和调用记录，不实现第二套象棋规则；下列断言中的输入由每项指定的测试准备步骤构造。

## 冻结来源与安装边界

- Gradle 8.13 bin ZIP SHA-256：`20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`。
- Android command-line tools 23.0：`https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip`；官方仓库 XML SHA-1：`e025545c62a8e64c7559119566a569fb1dec5f60`。保留 TLS 验证及官方校验类型，不编造 SHA-256。
- SDK 包：`platforms;android-36`、`build-tools;36.0.0`、`ndk;28.2.13676358`、`cmake;3.22.1`、`platform-tools`。
- SDK/Gradle 缓存放在 `/workspace/.toolchains`；`tools/dev.sh` 设置构建所需路径并原样传递子命令退出状态。不能覆盖全局 HOME、打印凭证、关闭 TLS 或修改锁文件来掩盖安装问题。
- 已核查目标域名：`dl.google.com`、`repo.maven.apache.org`、`plugins.gradle.org`、`plugins-artifacts.gradle.org`、`services.gradle.org`、`github.com`、`release-assets.githubusercontent.com`，均在当前网络预设内。后续显式拒绝按环境配置流程处理，不绕过代理。
- 引擎标签 `Pikafish-2026-09-06`，提交 `4c17cee11f888ae1d48a9494f2e2239f019f0a1f`。发行包 `Pikafish.2026-09-06.7z` 的官方 SHA-256 为 `41952bbfe2520faceb5902c69e6ab4845cc999841d2b49a95cc1be7867a25e5b`。
- 实施时先校验固定发行包，再检查并提取匹配 NNUE，计算其独立 SHA-256 写入 manifest。包内内容尚未下载，不假填权重哈希；没有匹配权重即停止该集成，不静默使用可变 `master-net`。
- 官方 Networks 权重另有使用条件，包括原文 “No commercial use without permission.”，不能宣称 GPLv3 自动覆盖 NNUE；按用户本次个人手机使用目标实施，任务 2 冻结许可原文，任务 10 按实际条款决定可交付包内容。
- 固定发行包是否包含 NNUE 尚未确认。如果没有，任务 2 继续核查官方版本记录及匹配权重来源并完成加载验证；缺失期间继续不依赖权重的识别、录屏和界面工作。全部独立工作完成后仍缺权重，才报告精确外部依赖，不能以文档记录代替完成引擎集成。
- 当前只验证了发布元数据，尚未安装工具或构建 APK。执行阶段先检查磁盘和可用核数；NDK 构建并行上限 3，避免同时编译多个优化变体耗尽资源。

## Task 1: 可构建 APK 与统一棋盘模型

**Files:** 创建根构建文件、wrapper、四个模块的 `build.gradle.kts`、`tools/bootstrap.sh`、`tools/dev.sh`；创建核心 `Model.kt`、`FenCodec.kt`、`Geometry.kt`、`EngineContract.kt`；创建 `app/src/main/AndroidManifest.xml` 和 `app/src/main/kotlin/dev/sakura/xiangqi/app/MainActivity.kt` 最小启动页。测试位于 `core/src/test/kotlin/dev/sakura/xiangqi/core/ModelTest.kt`、`FenCodecTest.kt`。

**Interfaces:** 产出上述公共契约；`FenCodec.decode(fen: String): Result<PositionSnapshot>`、`FenCodec.encode(snapshot: PositionSnapshot): String`、`GridCalibration.isValid(): Boolean`、`Board.apply(move: UciMove): Board`。应用 minSdk 29、compileSdk/targetSdk 36；不声明 INTERNET 或无障碍权限。

- [ ] **Step 1:** 写最小构建配置及模型测试：`startFenRoundTrip`、`invalidFenRejected`、`invalidCalibrationRejected`。断言初始局面 32 枚棋子、红先、`a0` 为红车；`decode("bad").isFailure`；零宽标定和范围外格点被拒绝。暂不实现模型。

```kotlin
assertEquals(32, FenCodec.decode(START_FEN).getOrThrow().board.cells.count { it != null })
assertEquals(Side.RED, FenCodec.decode(START_FEN).getOrThrow().sideToMove)
assertTrue(FenCodec.decode("bad").isFailure)
assertEquals(START_FEN, FenCodec.encode(FenCodec.decode(START_FEN).getOrThrow()))
```

- [ ] **Step 2:** 安装已冻结且校验通过的构建工具，生成 wrapper；运行 `./tools/dev.sh ./gradlew :core:test --tests '*ModelTest' --tests '*FenCodecTest'`，确认因缺失模型实现失败。
- [ ] **Step 3:** 实现模型、FEN 边界检查、基本行棋拷贝及几何范围检查；FEN 缺将帅、非法符号、非90格、错误行棋方和溢出计数均不能进入 JNI。加入最小启动页，固定依赖版本并启用 Gradle dependency locking。
- [ ] **Step 4:** 重跑任务测试及 `./tools/dev.sh ./gradlew :app:assembleDebug`，要求测试非零且全部通过，产出可解析 manifest 的 APK。这里不宣称引擎或录屏可用。
- [ ] **Step 5:** 提交此任务涉及的构建、契约和测试文件：`git commit -m 'feat: establish Android build and chess model'`。

## Task 2: 固定引擎、匹配权重及同源规则接口

**Files:** 创建 `tools/fetch-engine.py`、`third_party/pikafish/`、`third_party/pikafish-manifest.json`、`third_party/licenses/`；创建 `engine/src/main/cpp/CMakeLists.txt`、`engine_bridge.h`、`engine_bridge.cpp`、`jni_bridge.cpp`、`cpu_dispatch.cpp`、`host_probe.cpp`；创建引擎 `NativeEngine.kt`、`NetworkInstaller.kt`。测试为 `engine/src/test/python/test_native.py` 和 `engine/src/test/kotlin/dev/sakura/xiangqi/engine/NetworkInstallerTest.kt`。

**Interfaces:** `NativeEnginePort` 定义 `validate(history: PositionHistory): Result<LegalPosition>`、`configure(options: EngineOptions)`、`start(request: AnalysisRequest, callback: (EngineEvent) -> Unit)`、`requestStop()`、`awaitStopped()`、`close()`，由 `NativeEngine` 实现。`NetworkInstaller.ensureInstalled(): Result<File>`。宿主机 `host_probe legal --fen <fen> [--moves <moves>]` 返回 JSON `legalMoves` 和 `fen`；`host_probe search --fen <fen> --net <path> --movetime 1000` 返回 `bestMove`，错误时返回非零退出码及 `error`，使用与 JNI 相同的 `engine_bridge`。

- [ ] **Step 1:** 写规则、搜索和损坏权重测试。断言开局包含 `b0c2`、不含 `b0b2`；马腿被堵不能跳马、炮必须隔一子吃子、移开唯一遮挡导致将帅照面的走法被排除；缺将帅和非法历史返回错误。断言匹配权重能完成 1000ms 搜索并返回合法着法，损坏权重拒绝加载，复制中断不覆盖原有完整文件。

```python
self.assertIn("b0c2", legal["legalMoves"])
self.assertNotIn("b0b2", legal["legalMoves"])
self.assertIn(search["bestMove"], legal["legalMoves"])
self.assertNotEqual(corrupt_network_run.returncode, 0)
```

- [ ] **Step 2:** 运行 `python3 -m unittest discover -s engine/src/test/python -p 'test_native.py' -v`，确认因桥接程序缺失失败；运行 `./tools/dev.sh ./gradlew :engine:testDebugUnitTest --tests '*NetworkInstallerTest'`，确认对应实现未完成。
- [ ] **Step 3:** 按固定提交导入源码，检查校验后的发行包；实际含有匹配权重才提取并固定哈希及独立许可，否则继续核查官方匹配来源。使用原子临时文件复制权重，写入完成且校验通过后替换。基于冻结源码建立显式 C++ 源列表，排除 CLI `main.cpp`；生成通用和 dotprod 两种 JNI 库。另编译不含引擎的 ARMv8 基线 dispatcher，用 `getauxval` 检测能力后只装载一种引擎库，不能先装载 dotprod 库再检测。
- [ ] **Step 4:** 使用该提交的 `Attacks::init()`、`Position::init()`、`Position::set()` 和 `MoveList<LEGAL>` 构造独立规则 Position；保持重放历史的 `StateInfo` 地址稳定，使用 `UCIEngine::to_move` 校验每一步。搜索使用 `Engine::set_position/go/stop/wait_for_search_finished`，不访问私有 `Engine::pos`。构建宿主机探针并重跑 Step 2，以及 `:app:assembleDebug`；规则和搜索测试必须实际执行。
- [ ] **Step 5:** 提交源码、manifest、桥接和测试，不提交大权重或私钥：`git commit -m 'feat: integrate pinned Pikafish engine and rules'`。

## Task 3: 串行引擎服务与过期结果保护

**Files:** 创建引擎目录中的 `EngineActor.kt`、`EngineService.kt`、`BinderEngineGateway.kt`，两个 AIDL 文件及 `engine/src/main/AndroidManifest.xml`；测试 `EngineActorTest.kt`、`BinderProtocolTest.kt`，位于对应 `engine/src/test/kotlin/dev/sakura/xiangqi/engine/`。

**Interfaces:** `EngineActor.submit(request: AnalysisRequest)`、`cancel(searchId: Long)`、`updateOptions(options: EngineOptions)`；`BinderEngineGateway` 实现任务 1 的 `EngineGateway`。AIDL：`initialize(String optionsJson, IEngineCallback callback)`、`validate(long requestId, String historyJson)`、`analyze(String requestJson)`、`cancel(long searchId)`、`shutdown()`；回调 `oneway onEvent(String eventJson)`。JSON schema 包含事件类型、完整 AnalysisKey 或规则 requestId；不序列化原生指针。

- [ ] **Step 1:** 写 `stopAcknowledgedBeforeNextPosition`、`optionsWaitForSearchCompletion`、`lateEventKeepsOriginalKey`、`binderDeathClearsRequest`、`protocolRoundTrip`。可控假引擎必须在显式发出停止确认前阻止下一次 set_position；即使关闭旧流后收到回调也保留旧 key。

```kotlin
assertFalse(fakeNative.calls.contains("set_position:2")) // 尚未确认搜索1停止
assertEquals(firstRequest.key, lateEvent.key)
assertEquals(request, decodeRequest(encodeRequest(request))) // 协议测试内的编解码辅助
```

- [ ] **Step 2:** 运行 `./tools/dev.sh ./gradlew :engine:testDebugUnitTest --tests '*EngineActorTest' --tests '*BinderProtocolTest'`，确认测试因缺失 actor/协议实现而失败。
- [ ] **Step 3:** 实现引擎命令单一串行队列；Binder 线程只入队。取消请求立即转发，但设置局面、选项和销毁等待旧搜索完成。服务声明 `android:process=":engine"`、`exported=false`；初始化超过30秒或停止超过5秒则返回超时并重建自身引擎会话。
- [ ] **Step 4:** 重跑 Step 2，另验证 `Info/BestMove/Stopped/Failure` 全部携带原 key，Binder 断连后挂起规则请求和搜索均结束并可重新绑定。真实安卓 Binder/JNI 装载留给任务 10 的设备测试，不能用假引擎结果代替。
- [ ] **Step 5:** `git commit -m 'feat: isolate engine with serialized search lifecycle'`。

## Task 4: 录像标注、主题识别与动画过滤

**Files:** 创建 `tools/prepare-fixtures.py`，识别目录中 `PixelFrame.kt`、`GridLocator.kt`、`TemplateClassifier.kt`、`BoardRecognizer.kt`、`StabilityGate.kt`；模板和标签位于前述 resources 目录；测试 `vision/src/test/kotlin/dev/sakura/xiangqi/vision/RecognitionReplayTest.kt`、`StabilityGateTest.kt`。

**Interfaces:** `PixelFrame(width, height, argb: IntArray, timestampNs)`；`GridLocator.locate(frame: PixelFrame): GridCalibration?`；`BoardRecognizer.recognize(frame: PixelFrame, calibration: GridCalibration): BoardObservation`；`StabilityGate.accept(observation: BoardObservation): BoardObservation?`、`reset()`。初始门限：连续3帧完整标签一致；分类置信及运动门限写入带验证结果的主题配置。

- [ ] **Step 1:** 从附件提取棋盘裁剪；人工逐格标注开局、至少3个不同中盘稳定局面及吃子动画区间。开局用于提取模板，中盘至少2局面用于门限校准，至少1局面作为不参与调参的保留测试集，清单固定分组。写断言：每个稳定样本90格全部匹配；动画样本 `board==null` 或被稳定门拒绝；仅背景动画和选中光圈不会改变棋子标签。

```kotlin
assertEquals(90, expected.cells.size)
assertEquals(expected.cells, requireNotNull(actual.board).cells)
assertNull(gate.accept(animationObservation))
```

- [ ] **Step 2:** 运行 `./tools/dev.sh ./gradlew :vision:test --tests '*RecognitionReplayTest' --tests '*StabilityGateTest'`，确认算法缺失导致失败；测试集清单列出帧时间和标签来源，禁止把模型预测当人工真值。
- [ ] **Step 3:** 实现网格定位、手动校准输入、内部区域遮罩模板匹配、颜色区分和空位识别；低置信或遮挡不强制补棋。模板配置门限通过校准组固定并记录，保留测试集只用于最终验证；通过最新帧策略丢弃过时输入。
- [ ] **Step 4:** 重跑 Step 2；增加同一棋盘的缩放、翻转和压缩噪声输入，要求恢复相同规范棋盘。输出稳定帧与动画段分别的结果，失败样本保留用于诊断，不能把来源帧自匹配当验收。
- [ ] **Step 5:** `git commit -m 'feat: recognize JJ boards and reject animation frames'`。

## Task 5: 对局历史、悔棋及重新同步

**Files:** 创建核心 `Tracking.kt`、`MoveNotation.kt`；测试 `core/src/test/kotlin/dev/sakura/xiangqi/core/TrackingTest.kt`、`MoveNotationTest.kt`。

**Interfaces:** `GameTracker.start(snapshot: PositionSnapshot, historyComplete: Boolean): ConfirmedPosition`；`accept(observation: BoardObservation, legalMoves: List<UciMove>): TrackingUpdate`；`confirmRecovery(snapshot: PositionSnapshot, historyIndex: Int?): ConfirmedPosition`。更新为 `Unchanged`、`Confirmed`、`NeedsConfirmation`、`Unsynced`；后两者不携带可显示的箭头。`MoveNotation.format(snapshot, move, side): String` 输出双方规范数字及前后棋子消歧。

- [ ] **Step 1:** 写 `legalMoveAppendsOnce`、`undoRestoresTurn`、`restartClearsHistory`、`flipPreservesIdentity`、`missedMoveRequiresResync`、`ambiguousRepeatedBoardRequiresConfirmation`、`midgameHasIncompleteHistory`；断言相同观察不递增 revision，只有唯一合法变化才能追加，历史歧义不能自动选最近节点。

```kotlin
assertEquals(before.positionRevision + 1, after.positionRevision)
assertEquals(before.history.moves.size + 1, after.history.moves.size)
assertFalse(midgame.history.historyComplete)
assertTrue(ambiguousUpdate is TrackingUpdate.NeedsConfirmation)
```

- [ ] **Step 2:** 运行 `./tools/dev.sh ./gradlew :core:test --tests '*TrackingTest' --tests '*MoveNotationTest'`，确认对应状态与记谱实现缺失。
- [ ] **Step 3:** 实现规范棋盘与合法着法比较、历史截断和重新同步；用户确认行棋方独立于画面朝向。记谱包含黑方、进退方向及同一路多子的消歧，无法可靠格式化时显示明确起终点坐标。
- [ ] **Step 4:** 重跑 Step 2，并以任务 4 的人工标注序列驱动跟踪器；断言吃子后的棋子数、行棋方及历史与标注一致，所有无法恢复的跳变均进入重新同步状态。
- [ ] **Step 5:** `git commit -m 'feat: track legal game history and recovery'`。

## Task 6: 安卓录屏授权与前台捕获生命周期

**Files:** 创建应用目录中的 `ProjectionController.kt`、`CaptureService.kt`、`FrameReader.kt`；修改 `app/src/main/AndroidManifest.xml`；测试 `app/src/test/kotlin/dev/sakura/xiangqi/app/ProjectionLifecycleTest.kt`。

**Interfaces:** `ProjectionController.begin(resultCode: Int, consentData: Intent)`、`stop()`；`CaptureService.frames: Flow<PixelFrame>`；`FrameReader.readLatest(image: Image): PixelFrame?`。会话生命周期为 Idle/Starting/Capturing/Stopped；所有 Image 必须及时 close，处理 pixelStride、rowStride 及内容尺寸变化。

- [ ] **Step 1:** 写 `doubleStartCreatesOneSession`、`cancelledConsentStartsNothing`、`callbackRegisteredBeforeDisplay`、`stopIsIdempotent`、`rowPaddingDoesNotShiftPixels`、`resizeInvalidatesCalibration`。假平台记录授权消费和资源释放次数，Robolectric 固定运行 API 34。

```kotlin
assertEquals(1, platform.virtualDisplaysCreated)
controller.stop()
controller.stop()
assertEquals(1, platform.virtualDisplaysReleased)
assertEquals(1, platform.projectionsStopped)
```

- [ ] **Step 2:** 运行 `./tools/dev.sh ./gradlew :app:testDebugUnitTest --tests '*ProjectionLifecycleTest'`，确认缺失录屏生命周期实现。
- [ ] **Step 3:** 在前台用户操作取得授权后启动 mediaProjection 类型服务；声明 FOREGROUND_SERVICE、FOREGROUND_SERVICE_MEDIA_PROJECTION、悬浮窗权限及通知通道。注册 onStop 后创建唯一 VirtualDisplay；录屏 token 不复用。FrameReader 只复制需要的最新像素，3Hz 采样，其余帧立即释放。
- [ ] **Step 4:** 重跑 Step 2；验证锁屏/停止回调、异常初始化和重复停止都释放 VirtualDisplay/ImageReader/Projection，空白或目标不在画面时暂停识别并提示检查捕获范围。真机授权和 API 36 系统行为标为待设备执行。
- [ ] **Step 5:** `git commit -m 'feat: capture Android screen with explicit session lifecycle'`。

## Task 7: 棋盘坐标映射与悬浮提示

**Files:** 完成核心 `Geometry.kt`；创建应用 `OverlayController.kt`、`ArrowView.kt`、`OverlaySamplingGate.kt`；测试核心 `GeometryTest.kt` 和应用 `OverlayPolicyTest.kt`，放在各模块对应测试包。

**Interfaces:** `GridCalibration.center(square: Square, space: CoordinateSpace): FloatPoint`；`OverlayController.show(key: AnalysisKey, move: UciMove, text: String)`、`clear()`、`setPanelVisible(visible: Boolean)`；`OverlaySamplingGate.hideForSample(): Long` 返回隐藏后的时间边界，等待至少2个该时间之后的新帧。

- [ ] **Step 1:** 写 `all90CentersMapBothOrientations`、`letterboxIsAccountedFor`、`invalidCornersRejected`、`transformChangeClearsArrow`、`obscuringAlphaWithinSystemLimit`、`samplingWaitsForFreshFrames`。测试标定矩形为 `(41,367,545,910)`；断言翻转后 a0 映射到右上，捕获原点和屏幕原点不同仍能准确映射。

```kotlin
assertEquals(FloatPoint(545f, 367f), blackBottom.center(Square(0, 0), CoordinateSpace.DISPLAY))
assertTrue(arrowWindowAlpha <= minOf(0.6f, systemLimit))
assertFalse(sampleAcceptedWithOnlyOneFreshFrame)
```

- [ ] **Step 2:** 运行 `./tools/dev.sh ./gradlew :core:test --tests '*GeometryTest' :app:testDebugUnitTest --tests '*OverlayPolicyTest'`，确认对应实现缺失。
- [ ] **Step 3:** 实现可拖动顶部面板和非触摸箭头层，使用 TYPE_APPLICATION_OVERLAY；API31+ 查询最大触摸遮挡透明度，箭头窗 alpha 不高于 `min(0.6, systemLimit)`，避免多个叠加箭头窗。面板拖动范围限制在棋盘及 JJ 按钮之外，空间不足时折叠；几何改变立即清除。
- [ ] **Step 4:** 实现全屏录制的隐藏箭头采样流程；单应用录制仍验证是否包含助手内容。重跑 Step 2，检查隐藏到恢复期间收到的过期结果不会重现。实际点击穿透保留为真机验收项。
- [ ] **Step 5:** `git commit -m 'feat: render calibrated move hints without blocking play'`。

## Task 8: 应用完整流程与局面校正

**Files:** 完成应用 `MainActivity.kt`；创建 `SessionCoordinator.kt`、`CalibrationView.kt`、`BoardEditorView.kt`、`SettingsStore.kt`；创建 `app/src/main/res/values/strings.xml`；测试 `SessionCoordinatorTest.kt`、`BoardEditorTest.kt`、`SettingsStoreTest.kt`。

**Interfaces:** `SessionCoordinator.start(config: SessionConfig)`、`onFrame(frame: PixelFrame)`、`confirm(snapshot: PositionSnapshot, calibration: GridCalibration)`、`pause()`、`resume()`、`stop()`；`SessionConfig` 含执棋方、EngineOptions 和 3000/15000/30000ms 预算。协调器是应用中唯一发起搜索和更新悬浮提示的入口。

- [ ] **Step 1:** 写完整事件序列测试：稳定确认→规则查询→搜索→局面变化→撤下箭头→取消旧搜索→等待停止→验证新局面→新搜索。对所有 Info/BestMove 注入旧 session/revision/search/transform 值，断言均不显示；对方行棋时无我方箭头。

```kotlin
assertNull(recordingOverlay.visibleHint) // 已有新局面，再注入旧最佳着法
assertEquals(0, fakeGateway.requestsStartedBeforeOldStopAcknowledged)
assertNull(recordingOverlay.visibleHintWhenOpponentToMove)
```

- [ ] **Step 2:** 运行 `./tools/dev.sh ./gradlew :app:testDebugUnitTest --tests '*SessionCoordinatorTest' --tests '*BoardEditorTest' --tests '*SettingsStoreTest'`，确认协调器和编辑界面状态未实现。
- [ ] **Step 3:** 完成授权入口、开始/暂停/停止、执棋方与预算、棋盘缩略校验、两角标定和格点编辑；确认非法局面显示原因且不搜索。搜索进度显示深度、节点速度、评分和主要变化，最佳着法经当前合法列表检查后才显示。
- [ ] **Step 4:** 完成任务测试，加入新会话收到上一局恢复数据、授权撤销时重复停止、格式错误/空最佳走法、终局、引擎断连及人工修改行棋方等用例。增加 `scoreUsesPlayerPerspective`：引擎给当前红方 +120 厘兵、用户执黑时显示 -120，执红时显示 +120；将杀分单独格式化。支持完整历史/历史不完整提示；设置持久化但不得自动把上一局当当前局面确认。
- [ ] **Step 5:** `git commit -m 'feat: connect recognition and engine into Android assistant'`。

## Task 9: 资源控制、恢复与可诊断错误

**Files:** 创建应用 `ResourcePolicy.kt`、`SessionStore.kt`、`DiagnosticExporter.kt`；完善引擎 `NetworkInstaller.kt`、`EngineActor.kt`；测试 `ResourcePolicyTest.kt`、`SessionStoreTest.kt`、`DiagnosticExporterTest.kt` 及原有权重测试。

**Interfaces:** `ResourcePolicy.onThermalStatus(status: Int): ResourceAction`、`onMemoryPressure(): ResourceAction`；`ResourceAction.None/Pause/Retry(options: EngineOptions)`。`SessionStore.save(position: ConfirmedPosition)`、`loadCandidate(): ConfirmedPosition?`；`DiagnosticExporter.export(boardCrop: PixelFrame, log: List<DiagnosticEvent>): Uri`，仅用户显式触发。

- [ ] **Step 1:** 写 `severeThermalPausesSearch`、`memoryFallbackNeedsRetry`、`corruptStoredSessionNotConfirmed`、`diskFullDoesNotPublishPartialNetwork`、`exportContainsOnlyBoardCrop`。断言内存降档为2线程/128MiB，热暂停清箭头，损坏会话不自动恢复，诊断没有头像区域和凭证字段。

```kotlin
assertEquals(ResourceAction.Pause, policy.onThermalStatus(PowerManager.THERMAL_STATUS_SEVERE))
assertEquals(ResourceAction.Retry(EngineOptions(2, 128, 1)), policy.onMemoryPressure())
assertNull(storeWithCorruptJson.loadCandidate())
```

- [ ] **Step 2:** 运行 `./tools/dev.sh ./gradlew :app:testDebugUnitTest --tests '*ResourcePolicyTest' --tests '*SessionStoreTest' --tests '*DiagnosticExporterTest' :engine:testDebugUnitTest --tests '*NetworkInstallerTest'`，确认对应保护未实现。
- [ ] **Step 3:** 实现温控监听、内存压力后的有序停止和降档、历史原子保存及恢复候选；不在内存异常后的同一损坏搜索上继续运行。权重验证失败、存储不足及引擎超时都映射到明确可重试状态。
- [ ] **Step 4:** 重跑 Step 2；检查应用 manifest 不含网络和无障碍权限，后台不写整屏帧。引擎/捕获/悬浮错误停止路径均由统一协调器清理，不无限自动重启。
- [ ] **Step 5:** `git commit -m 'feat: bound resources and recover assistant sessions safely'`。

## Task 10: APK、对应源码与验收记录

**Files:** 创建 `tools/verify-apk.sh`、`README.md`、`docs/build.md`、`docs/device-validation.md`、`docs/validation-results.md`、`LICENSE`、`THIRD_PARTY_NOTICES.md`；创建 `app/src/androidTest/kotlin/dev/sakura/xiangqi/app/EngineSmokeTest.kt`、`AssistantFlowTest.kt`。

**Interfaces:** `./tools/verify-apk.sh <apk>` 验证签名、manifest、ABI、16KB 对齐、权重版本和不含私钥；设备测试分别标记“引擎 Binder/JNI”和“授权录屏/悬浮流程”，不冒充已在 JJ 真机验证。

- [ ] **Step 1:** 写安卓 EngineSmokeTest：实际绑定 `:engine`，装载匹配权重，提交已知局面并确认返回合法走法；取消后切局面，确认旧结果不显示。AssistantFlowTest 在测试 Activity 内显示棋盘样本，经过真实捕获和悬浮链路完成一次提示；系统授权通过明确测试步骤完成，不能硬编码授权 token。

```kotlin
assertTrue(valid.legalMoves.contains(requireNotNull(best.move)))
assertEquals(request.key, best.key)
assertFalse(eventsFromNewSearch.any { it.key == cancelledRequest.key })
```

- [ ] **Step 2:** 先运行 `./tools/dev.sh ./gradlew :core:test :vision:test :engine:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug` 及宿主机引擎测试，保存当前运行结果。若有安卓设备，运行 `./tools/dev.sh ./gradlew :app:connectedDebugAndroidTest`；无设备则记录未运行，不把测试 APK 编译成功写成设备通过。
- [ ] **Step 3:** 实现 APK 检查脚本；用 build-tools 的 `apksigner verify --verbose`、`zipalign -c -P 16 -v 4` 和 LLVM ELF 检查验证签名、ZIP及原生 PT_LOAD 对齐。核对实际打包权重 SHA-256 和冻结 manifest，列出 ARMv8/dotprod 变体及设备选择策略。
- [ ] **Step 4:** 编写可重建对应版本的说明，保存 GPLv3 和独立 NNUE 许可原文；发行条件未满足时明确阻塞原因，不交付不匹配或未验证权重。生成 APK、源码归档和构建 manifest，开发签名私钥留在忽略路径，不进入归档。
- [ ] **Step 5:** 按设计完成目标手机验收清单：双方各至少5次落子及吃子/悔棋/翻转/重开，录屏停止与重启，箭头点击穿透，默认档连续15分钟的耗时/速度/内存/温度/耗电。无法远程操作手机的项目交付为待用户实测，并给出可复现步骤；记录通过、失败、跳过和未运行的区别。
- [ ] **Step 6:** 在本轮最终文件上执行上述构建和对应受影响检查，`git diff --check` 后提交交付说明：`git commit -m 'docs: package assistant and record validation evidence'`。只在具备证据时报告 APK 构建成功或具体功能通过。

## 计划覆盖与执行交接

设计第1–3节由任务1、8、10覆盖；第4–5节由任务1、4、5、8覆盖；第6节由任务2、3、9覆盖；第7节由任务6、7、8覆盖；第8–9节由任务2、9、10覆盖。已逐项检查接口名、坐标约定、默认值、五类 Review Focus 和本地/设备验证边界。

推荐由主代理在当前会话直接执行：十项任务的状态、规则和捕获接口联系紧密，共享上下文可以降低重复交接成本；完成后进行一次独立整体审查。用户也可选择逐任务子代理实现和逐项独立审查。用户审阅本计划并选定方式后，按对应执行技能开始；当前不安装产品依赖、不生成应用代码。
