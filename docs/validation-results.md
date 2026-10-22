# 验证记录

2026-10-22，Linux x86_64 云环境；目标为 Android ARM64。没有可操作的 Android 手机，本文件区分宿主测试、APK 检查和设备测试。

| 检查 | 结果 | 实际覆盖 |
|---|---|---|
| core | 19 通过 | FEN、90 格坐标、双方翻转、规则历史与中文走法 |
| vision | 13 通过 | 人工标注的校准/保留样本全部 90 格，缩放、翻转、JPEG 干扰，吃子动画拒绝，稳定帧门控 |
| engine JVM | 14 通过 | 搜索串行取消、完整 key 协议、权重校验、复制中断和模拟存储失败 |
| app JVM/Robolectric API34 | 42 通过 | 生命周期、采样隔离、旧结果拒绝、走法合法性、轮次、断连、暂停、恢复候选、设置/校准保存、资源策略和诊断导出 |
| 宿主机实际 Pikafish | 10 通过 | 真装载匹配 NNUE，初始合法走法、双方、马腿、炮架、将帅、应将、非法历史、终局、损坏权重拒绝及实际搜索 |
| JNI 分配故障模拟 | 8 通过 | 编译生产 jni_bridge.cpp，使用测试 shim 注入数组、字符串、全局引用、类查找及 C++ 分配失败；不代表 ART 真机执行 |
| APK assembleDebug | 通过 | NDK 实际 ARM64 两个引擎变体及基线分发库，离线权重已打包 |
| 测试 APK assembleDebugAndroidTest | 通过编译 | 包含真实 Binder/JNI 和人工授权录屏流程测试；未执行设备测试 |
| APK 校验 | 通过 | apksigner 签名、zipalign 16 KiB、全部 ELF PT_LOAD 16 KiB、ARM64-only、权重 SHA-256、无联网/无障碍权限、无私钥、含许可证 |
| Bootstrap 重复执行 | 通过 | 不改全局 HOME、无需提权；JDK/SDK/NDK/CMake 可重复使用 |
| Android 真机 / JJ / iQOO | **未运行** | 系统授权、Binder/JNI 在手机上的装载、箭头点击穿透、OriginOS 后台、热/内存回调和 15 分钟性能/电量 |

最终构建及 APK 的 SHA-256、证书摘要、版本和对应源码提交记录在交付的 `build-manifest.json`。检查命令：

```sh
./tools/dev.sh ./gradlew :core:test :vision:test :engine:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
python3 -m unittest discover -s engine/src/test/python -p 'test_*.py' -v
./tools/verify-apk.sh app/build/outputs/apk/debug/app-debug.apk
```

源码对应修复验证：先写失败测试，再实现设置/校准持久化及初次同尺寸回调过滤，最后整套构建通过。校准仅在捕获尺寸、物理显示尺寸及底方均相同时复用，复用校准不复用上一局确认。

宿主搜索成功不代表 Android Binder/JNI 已在手机通过。云容器没有可用 USB 手机通道；本次尝试 `adb devices` 时，adb 还因只读 `/home/agent/.android` 无法初始化，未扩大文件权限或修改全局 HOME。该限制只影响本机设备调试；Android 测试 APK 已编译，具体真机操作见 [device-validation.md](device-validation.md)。

录像只有一个主题和有限对局，识别结果不能外推到其他 JJ 主题、字体、全部残局或所有动画。行棋方来自用户确认和连续合法走法，不能从单张图片推断。历史不完整会明确显示，重复局面判断可能受限。

第一版只请求整屏录制以固定物理坐标；默认不写整屏图片，诊断只接受已识别稳定棋盘。没有 Root、无障碍点击和自动落子。严重温控需要用户降温后手动继续；内存回退后需用户重新开始，不无限自动重启。

云环境的 install_script 和 start_skill 已保存为配置草稿；当前构建已验证，发布环境快照仍由用户在环境设置中执行，尚未声称发布成功。

独立复核及一次修复的证据见 [code-review.md](code-review.md)，全部取舍和暂缓小项见 [implementation-decisions.md](implementation-decisions.md)。暂停后重新校验、手工校正基线、过期文字撤销、采样代次、尺寸失效锁定、取消顺序、初始化中停止、JNI 空引用及资源重试均已补充验证。
