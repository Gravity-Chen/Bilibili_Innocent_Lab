# 原时钟关联研究探针 0.9

这是独立 Android/Modern Xposed 研究工程，不参与正式模块的 Gradle 工程，也不开放 FPS 设置。
它保留已验证的 ShadowHook 加载方式和四个原时钟观察点，供后续关闭真实时钟、帧所有权和呈现恢复边界。

## 构建与本地核验

需要 Python 3、JDK 17、Android SDK 37、NDK 29.0.14206865、CMake 3.31.5。
使用仓库现有的 Gradle wrapper；SDK/JDK 可以通过环境变量或参数提供。

```powershell
python research/frame-clock-probe/build.py --sdk "$env:LOCALAPPDATA/Android/Sdk"
python research/frame-clock-probe/verify.py --sdk "$env:LOCALAPPDATA/Android/Sdk"
```

构建从声明的 Maven 仓库解析依赖，核对固定校验和；不需要本机 `reference/` 文件夹。
`build.py` 仅为自己的构建子进程清除会干扰 Prefab stderr 的 `JDK_JAVA_OPTIONS`，不改用户环境或主工程启动脚本。
`verify.py` 执行 270 项已有 JVM 检查，核对双 ABI JNI 导出、ELF 对齐、依赖字节、ZIP 边界和 Lint。
构建产物与本地报告保留在各 `build/` 目录中，不应提交。

## 已验证范围

2026-10-18 的原始研究候选 0.9（APK SHA-256 `ac833978729c948e0700ab2e9e6d35537c7b8c3c8612fb81395e01b13680264f`）
在 Android 17 ARM64/LSPosed 环境完成长视频与 Story 各一次短窗口，合计1813条观察，无回执丢弃或截断。
四个函数在 `:ijkservice` 安装成功；主进程没有匹配库，因此没有安装。
验证后研究模块已停用、移除作用域、卸载，宿主已重启并核对恢复。
本公开工程调整了依赖获取和构建入口；重新生成的 APK 尚未安装，不能直接沿用原候选的 APK 运行验收。

前一阶段的自有函数探针已验证并发安装、16个 ABI 样例、原调用与解钩后基线。
这里的真实播放器观察采用进程存活期间保留原调用的策略，结束采集后通过停止并重启宿主解除映射。

## 运行边界

- 包名为 `research.innocent.frame.observer`，目标仅为 `tv.danmaku.bili` 的主进程和 `:ijkservice`。
- 仅精确宿主8902100、ARM64、匹配的 ELF build-id、函数地址、指令前缀和可读范围允许安装。
  ARM32 只构建，不安装播放器钩子。不能据此宣称全部35份样本运行兼容。
- ShadowHook 使用 unique 模式，经私有 ClassLoader 的原生库搜索目录加载，走 C API 初始化。
  `native_init` 只观察框架元数据，不调用框架 Hook 指针，不在库加载回调安装播放器钩子。
- 安装和解析位于有界后台线程。观察最多12秒，固定容量槽位与队列；结束后继续原调用。
- 原生回调不访问磁盘、网络、JNI或全局DEX，不解引用播放器私有对象布局，不改原返回值。
  只输出地址相等关系的整数token、调用括号、原始数值和返回码。
- 不采集画面、音频载荷、账号或设置，不使用模型/GPU，不修改 Surface。ShadowHook 自身会初始化信号和 linker 机制。
- 真机操作需要独占窗口；必须在结束时停用研究模块、移除作用域、卸载、清理已核对的研究输出并重启宿主。
  本工程的构建和核验脚本均不调用 ADB。

这些数据还不能生成 `FrameTimeline.ClockSnapshot`：速度/暂停、队列与媒体代次、PTS原点、跨进程帧关联、未来帧所有权和呈现恢复仍未闭合。
启动时已实际观察到内层 audio master 约为-0.409秒、外层播放位置为0；不能以常数偏移或进度斜率补齐这些状态。

## 固定依赖与许可

- ShadowHook 2.0.1，源码 commit `854c775c2c3676e57a0f383597ebf420b5204161`，MIT。
  官方 AAR SHA-256 `2cad01ff4d59542958775e19f281981a7e84b39670b5fae75905b8511e10c7cd`。
- libxposed API 102.0.0，只用于编译；AAR及其中 `classes.jar` 都核对固定哈希，不打包框架API实现。
- 本项目代码与必要许可说明随源码保留在 `app/src/main/assets/licenses/`。
  此工程不包含模型、预编译依赖、设备记录或本机配置；正式模块现有 `VisionTool.zip` 的内容不受此工程影响。
