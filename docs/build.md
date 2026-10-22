# 构建和复现

云环境是独立 checkout，直接使用 `/workspace/JJ-chess`；无需新建 worktree。需要 Linux x86_64、Python 3.11+、curl、unzip、git、g++ 和约 8 GiB 空间。安装脚本使用校验值和系统 CA，不关闭 TLS 验证。

```sh
cd /workspace/JJ-chess
./tools/bootstrap.sh
python3 -m pip install --target /workspace/.toolchains/python-libs py7zr==1.0.0
python3 tools/fetch-engine.py
./tools/dev.sh ./gradlew :core:test :vision:test :engine:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --console=plain
./tools/verify-apk.sh app/build/outputs/apk/debug/app-debug.apk
```

版本：JDK 21.0.12.1+1、Gradle 8.13、AGP 8.11.1、Kotlin 2.2.21、Android SDK/Build-tools 36、NDK 28.2.13676358、CMake 3.22.1；minSdk 29、targetSdk 36。固定网络约 50.7 MB，已经打包在 APK；手机运行不需要下载。新环境可通过校验固定官方发布包重新准备权重。源码归档已含网络，直接构建可跳过 fetch-engine。

`tools/dev.sh` 配置工作区工具路径及云环境代理/CA。设备和网络之外不需要凭证。`.local-signing/debug.keystore` 是本地开发签名，仅本机保留，禁止提交；在另一台环境生成新密钥的 APK 不能直接覆盖此版，需要卸载旧包或使用自己的持续签名。构建过程可复现版本及功能，不承诺不同签名和主机的 APK 字节一致。

宿主机原生引擎测试（真的装载权重、搜索、规则校验）：

```sh
./tools/dev.sh cmake -S engine/src/main/cpp -B .build/native -G Ninja -DCMAKE_BUILD_TYPE=Release
./tools/dev.sh cmake --build .build/native -j 3
python3 -m unittest discover -s engine/src/test/python -p test_*.py -v
```

测试 APK 仅编译不代表真机通过：

```sh
./tools/dev.sh ./gradlew :app:assembleDebugAndroidTest --console=plain
./tools/dev.sh adb devices
# 接入测试手机后：
./tools/dev.sh ./gradlew :app:connectedDebugAndroidTest --console=plain
# 整条捕获测试需预先授予悬浮权限，并在测试弹窗手动授予新的录屏 token：
./tools/dev.sh ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.runCaptureFlow=true --console=plain
```

`EngineSmokeTest` 跨真实 Binder/JNI 执行搜索与取消；`AssistantFlowTest` 在调试专用非导出 Activity 上绘制棋盘样本，走真实 MediaProjection/识别/搜索/悬浮链路。后者默认有显式运行条件，需要人工授权，不能硬编码系统录屏令牌。调试 Activity 无桌面入口，不能从外部应用启动。

`tools/package-source.py` 在提交后从 Git 跟踪文件生成源码包，并加入匹配网络及构建清单；排除所有已发布的 dist APK、旧源码包及校验文件，包名跟随 Android versionName；不枚举整个工作区，不包含钥匙、缓存、原始视频或环境凭证。发布 APK 时应同时保留对应源码、GPL 和独立权重许可。

源码包筛选回归：`python3 -m unittest discover -s tools/tests -v`。
