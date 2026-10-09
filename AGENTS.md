# Repository Guidelines

## 项目结构与模块组织
- `app/`：Android 应用模块。业务代码位于 `app/src/main/java/com/pengxh/daily/app/`，按 `ui`、`service`、`utils`、`sqlite`、`retrofit`、`adapter`、`model` 和 `widget` 分类。
- `app/src/main/res/`：布局、图标、主题、字符串等资源；`app/src/main/assets/` 存放随应用打包的 JSON 数据。
- `lite/`：供 `app` 使用的通用 Android 库。共享 UI、网络、Socket 和扩展工具应优先放在这里。
- `design/`、`screenshot/`：设计原型和文档图片。行为或架构变化时，同步更新 `README.md`、`CHANGELOG.md` 或 `REVIEW.md`。
- `app/src/test/java/`：JVM 单元测试目录，当前使用 JUnit 4 覆盖工作日计划等纯逻辑；项目仍没有 `androidTest/` 设备测试目录。
- cloudfunctions/relay/：CloudBase 云函数，负责设备配对、配置 revision、指令队列和考勤记录。
- miniprogram/：微信小程序控制台，只通过云函数读写数据，不直接连接 Android。

## 构建、测试与开发命令
开发环境需使用 JDK 17 和 Android SDK 36。

```powershell
.\gradlew.bat assembleDebug          # 构建 app 与 lite
.\gradlew.bat :app:installDebug      # 安装到已连接的设备或模拟器
.\gradlew.bat :app:lintDebug         # 运行 Android Lint
.\gradlew.bat test                   # 运行现有 JVM 测试
```

建议使用 Android Studio 打开仓库根目录。除非确有需要，不要更换 Gradle Wrapper 或修改签名配置。

## 编码风格与命名规范
遵循 Android/Kotlin 官方风格，统一使用四空格缩进。新代码优先使用 Kotlin；仅在延续现有 Java 数据库实体或兼容代码时保留 Java。类使用 `PascalCase`，函数和属性使用 `camelCase`，常量使用 `UPPER_SNAKE_CASE`。资源文件使用 `activity_main.xml`、`widget_view_keyboard.xml` 等小写下划线命名。Activity、Fragment 和 Service 应带角色后缀；提交前使用 Android Studio 格式化并运行 Lint。

## 测试指南
项目当前使用 JUnit 4 编写 JVM 测试，未设置覆盖率门槛。新增测试时，JVM 测试放在 `app/src/test/java/`，设备测试放在 `app/src/androidTest/java/`，并补充相应依赖。测试类命名为 `FeatureBehaviorTest`，方法采用 `methodUnderTest_condition_expectedResult`。分别运行 `.\gradlew.bat testDebugUnitTest` 和 `connectedDebugAndroidTest`。通知监听、定时调度、截屏、悬浮窗、前台服务及权限流程必须在真机上手动验证。

## 提交与 Pull Request 规范
提交信息遵循 Conventional Commits，可带模块范围，例如 `feat(design): ...`、`fix(service): ...`、`refactor(scheduler): ...` 或 `chore: ...`；中文描述可以使用。每个提交应保持单一目的。PR 需说明行为变化、风险和验证方式，关联相关 Issue；涉及 UI 或权限流程时附截图或录屏，并注明测试设备与 Android 版本。

## 文档回写与变更记忆
- **强制回写**：每次提交代码或完成一个功能后，必须把本次变更回写到 `AGENTS.md` 与 `README.md`，保证文档与实现同步。
- 回写内容至少覆盖：功能变化、受影响模块 / 文件、配置或行为变化、验证方式。
- 分工：`AGENTS.md` 记录影响后续开发的规则、结构、约束与上下文；`README.md` 记录面向用户的功能与使用说明；版本级变化同时写入 `CHANGELOG.md`。
- **形成记忆**：每次变更都要在文末「变更记忆」小节追加一条记录，沉淀为可追溯的项目记忆，供后续任务快速了解上下文。
- 若变更使本文档既有描述失效（如目录结构、构建命令、模块清单），必须一并修正。

## 安全与配置说明
谨慎处理通知访问、屏幕投射、悬浮窗和前台服务逻辑。未经讨论，不要引入分析上报、远程上传或新的密钥。发布密钥库和签名参数已纳入仓库，不要擅自更换。修改调度、截屏、遮罩或服务生命周期前，先阅读 `REVIEW.md`。
远程中继依赖 HTTPS、设备 Token 和云函数权限校验；禁止把 Token、邮箱授权码或企业微信 Webhook Key 写入日志、导出配置或小程序。

## 变更记忆

> 每次提交或完成功能后在此追加，最新记录置顶。

### 2026-10-09 修复远程配对主线程数据库异常
- `RemoteConfigManager.snapshot()` 在配对阶段读取 Room 任务表时触发 `Cannot access database on the main thread`，导致首次配对失败。
- 任务快照和任务替换现在显式切换到 `Dispatchers.IO` 执行，配对请求构造不再阻塞主线程。
- 验证：JDK 17 下 `testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug` 通过。

### 2026-10-09 开发 CloudBase 远程中继首版
- 新增 `cloudfunctions/relay`：实现设备配对、Token 哈希、配置 revision、指令队列、ACK、考勤去重上报和小程序云函数接口。
- 新增 `miniprogram`：提供设备列表、配对、状态查看、启动/停止任务、立即打卡、循环开关、考勤查询和任务/工作日配置页面。
- Android 新增 `remote` 包：`RemoteRelayStore`、`RemoteRelayClient`、`RemoteRelayCoordinator`、`RemoteConfigManager`、`RemoteCommandProcessor`、`RemoteSyncWorker` 和 `RemoteSyncScheduler`。
- `ForegroundRunningService` 集成 60 秒同步循环，WorkManager 提供 15 分钟兜底；新增开机广播 `BootReceiver`。
- `NotificationMonitorService` 将目标应用打卡通知转换为云端考勤记录，并按 `dedupeKey` 防止重复上报。
- 设置页新增「远程中继」入口，可填写 HTTPS 地址、设备名称和 6 位配对码。
- 保留旧 `DT#` 聊天指令作为过渡，邮件和企业微信 Webhook 仍负责结果通知，邮件授权码与 Webhook Key 不上传云端。
- .gitignore 增加 cloudfunctions/**/node_modules/ 和 miniprogram_npm/ 忽略规则。
- CloudBase HTTP 访问优先使用环境自带的免费默认域名；默认域名有频率、有效期和稳定性限制，自定义域名属于付费套餐能力。
- 验证：JDK 17 下执行 `testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug` 通过；小程序和云函数 JavaScript 已通过 `node --check`，JSON 文件解析通过。


### 2026-10-09 修复大小周本周类型选项未显示
- 将「选择本周类型」弹窗从 `setSingleChoiceItems()` 改为 `dialog_work_week_type.xml` 自定义 `RadioGroup`，确保“本周是小周 / 本周是大周”两个选项稳定展示。
- `TaskConfigActivity.kt` 通过 `DialogWorkWeekTypeBinding` 读取选中项，再计算大周锚点并保存，取消操作仍不写入配置。
- 验证方式：执行 `testDebugUnitTest`、`:app:lintDebug`、`assembleDebug`，并在真机确认弹窗包含两个单选项。

### 2026-10-09 工作日设置支持大小周
- 新增 `WorkdayScheduleResolver.kt`，用纯日期逻辑统一处理固定工作日和大小周交替；大周锚点按周一存储，支持跨月、跨年计算。
- `CustomWorkdayManager.kt` 新增模式、小周工作日、大周工作日和大周锚点配置，同时保留 `CUSTOM_WORKDAYS_KEY` 作为旧版固定工作日兼容层。
- `TaskScheduler.kt` 统一使用 `isRestDay()`；节假日显示/跳过优先级调整为“补班日 > 节假日 > 工作制休息日 > 工作日”。
- `TaskConfigActivity.kt` 增加“每周固定 / 大小周”模式选择，并按“小周工作日、大周工作日、本周类型”完成配置。
- `ExportDataModel.java`、`TaskDataManager.kt` 支持大小周配置导入导出；旧 JSON 无新字段时仍按固定工作日导入。
- 新增 `WorkdayScheduleResolverTest.kt` 覆盖固定模式、大小周交替、跨年和缺少锚点回退。
- 补充 `email_config_top_bar_menu.xml` 菜单项标题，清除阻断构建的既有 `MenuTitle` Lint Error。
- 验证：`testDebugUnitTest`、`:app:lintDebug`、`assembleDebug` 通过（JDK 17）。

### 2026-10-09 移除免费软件相关提示
- 删除 `WatermarkDrawable.kt`、`DailyTask.kt` 及 `app/src/main/cpp/` 整个 native 模块。
- 首页 `MainActivity` 与 `SettingsActivity` 不再叠加背景水印。
- 设置入口移除「使用须知」弹窗，改为直接进入设置页。
- 清理 `app/build.gradle` 中失效的 `externalNativeBuild` / `ndkVersion` / `abiFilters` 配置。
- 提交：`chore(ui): 移除免费软件背景水印与使用须知弹窗`；验证：`:app:assembleDebug` 构建通过（JDK 17），APK 中无 `免费` / `咸鱼` / `使用须知` 字样。

### 2026-10-09 整理项目文档
- 重写 `README.md`，移除原作者宣传、争议说明、社区群号、授权与警示信息，仅保留功能相关内容。
- `AGENTS.md` 新增「文档回写与变更记忆」规则与「变更记忆」小节。
- 修正 `AGENTS.md` 中已失效的描述（移除 `app/src/main/cpp/` 与 NDK / CMake 依赖）。
