# 凝光稳定版接入

当前使用上游 [1.5.0 Stable Release](https://github.com/jichuo1/LumenCoacervationEngine/releases/tag/1.5.0)，`lumen-engine` 与 `lumen-motion` 在 `gradle/libs.versions.toml` 中共用同一个版本。Gradle 直接从 JitPack 解析官方 Maven 产物，不再下载源码归档、对齐上游工具链或通过 `includeBuild` 替换依赖，也不提供本地开发源码覆盖。

## 底栏阴影

`HostBottomBarShadow` 直接调用正式版公开的 `LumenControlShadow.shadowOnly()` 和 `LumenControlShadowOptions`。阴影算法由引擎提供，模块只负责把 Drawable 放到父级 overlay，避开底栏自身的圆角裁剪，并同步位移、透明度、可见性和 detach 生命周期；不包裹 Session 持有的背景，保持玻璃采样与背景所有权。浅色使用环境影和接触带，深色保留原有亮色描边。

此前为了取得尚未发布的软阴影 API，settings 固定下载上游开发提交并编译；没有把阴影渲染器复制进模块业务源码。1.5.0 已正式发布该 API，因此保留宿主接线即可。

`HostBottomBarShadowInstrumentedTest` 检查外侧像素、内部纯净、移动、明暗切换、隐藏与释放。亚克力、云母、玻璃会话和动效继续使用各自公开接口。

## 源码契约测试

部分宿主动效兼容名称委托到引擎类型。`LceMotionSource` 先核对宿主委托，再读取与二进制依赖同版本的官方 `sources.jar`，保留原有语义护栏。`lumenContractSources` 是不传递依赖的独立测试配置，源码包计入测试缓存输入，不参与 APK 编译或打包。普通 Debug 构建不需要解析源码包。

首次构建需要联网取得正式依赖及测试所需的源码包，缓存齐全后支持 `--offline`。使用本项目既有 JDK 17、Gradle wrapper、Android SDK 和本地缓存配置。

## 本次验证（2026-10-18）

Debug APK、测试 APK、3359 项 JVM 测试（0 失败、1 项跳过）与快速 Lint 通过；244 条警告，原有 3 个错误基线未扩展。离线运行依赖报告确认 core/motion 均解析为 1.5.0 的正式 AAR。构建结束已停止并确认无 Gradle/Kotlin 守护进程。

默认手机覆盖安装后，底栏阴影、玻璃视口与连续性、亚克力、云母共 14 项设备回归全部通过。系统后台启动拦截通过检查实际测试栈后显式启动同一 Activity 恢复，未修改系统安全策略。真实宿主冷启动后，日志确认底栏增强已加载且 Hook 安装无失败，截图检查底栏与卡片正常显示。报告、测试输出、截图和加载日志保存在忽略目录 `captures/lumen-stable/`。

## 升级

只在 catalog 中统一更新 Lumen 版本，核对该版本的官方发布说明、公开 API 和依赖要求，再运行模块构建、JVM 测试及适用的 Lint。涉及材质或生命周期变化时，补充设备回归，重点检查顶底栏采样、明暗主题、气泡/面板动效、detach 与重绑定后的资源和背景归属。

1.2.3 已吸收历史的线性淡出、表面及动效扩展，1.4.1 已吸收表面连续性修复，1.4.6 已吸收亚克力保色修复。旧补丁已移除，历史实现和当时的验证记录可从 Git 历史查询；当前构建不应用任何 Lumen 补丁。
