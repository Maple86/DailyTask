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

### 2026-10-13 移除云端配置同步，仅保留远程快捷操作
- 产品决策：小程序不再管理任务时间与工作日配置，任务/工作日/目标应用等全部由 Android 端本地设置管理，云端只下发快捷操作指令。
- Android 移除：删除 `RemoteConfigManager`；`RemoteRelayCoordinator` 不再处理配置下发（移除 `applyConfig`、`stableConfigHash`、`normalizeJson`、`configHash`、配对时的 `initialConfig`）；`RemoteRelayStore` 删除 `configRevision` / `configHash`；`DeviceStatusCollector` 不再上报 `appliedConfigRevision`；`RemoteRelayActivity` 不再展示配置版本。
- Android 新增：`RemoteCommandProcessor` 支持 `TOGGLE_LOOP` 指令，切换本地 `TASK_AUTO_RECYCLE_KEY` 并回传结果与消息通知，取代原先通过写云配置实现的循环开关。
- 保持：`START_TASK` / `STOP_TASK` / `TRIGGER_CLOCK_IN` / `QUERY_STATUS` 行为不变；打卡记录与 `records` 页面保留。
- 验证：`:app:compileDebugKotlin`、`testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug` 全部通过。

### 2026-10-13 修复远程同步线程崩溃（JsonNull 类型转换）
- 现象：安装新版本后打开 App 立即闪退；真机 logcat 显示 `FATAL EXCEPTION: DefaultDispatcher-worker-1`。
- 根因：服务端在「配置无变化」时返回 `"config": null`（Gson 解析为 `JsonNull`），而 `RemoteRelayCoordinator.applyConfig()` 直接调用 `data.getAsJsonObject("config")`。Gson 该方法对 `JsonNull` 会抛 `ClassCastException`（而非返回 null），异常在 `Dispatchers.IO` 的同步协程中未被捕获，直接杀死进程。
- 修复：新增 `JsonExtensions.kt` 提供 `objectOrNull` / `arrayOrNull` / `stringOrEmpty` / `intOrZero` / `booleanOrNull`，统一做 Json 类型判断；`applyConfig` 改为先判断 `isJsonObject`；`RemoteRelayClient` 的 `ok` / `error` / `data` 一并改用安全取值。
- 加固：轮询循环、`syncNow()`、指令执行协程全部包 `runCatching` 并记录日志，避免任何单次异常终止后台同步或杀死进程。
- 新增回归测试 `JsonExtensionsTest`：固化「Gson 原生取值遇 null 抛异常」与「安全取值返回 null」两种行为，覆盖 config / commands / data / ok 四类字段。
- 验证：真机（OPPO PADM00，Android 目标 SDK 36）安装后启动稳定运行 90 秒无崩溃、无 JsonNull 异常；`testDebugUnitTest`（含新测试）、`:app:lintDebug`、`:app:assembleDebug` 全部通过。

### 2026-10-13 修复云端配置长期不应用
- 现象：小程序更新任务与工作日配置后，Android 端长时间不刷新，需反复杀进程重进才生效。
- 根因：`RemoteRelayCoordinator.applyConfig()` 仅当 `configRevision > RemoteRelayStore.configRevision` 时才应用配置。一旦本地版本号因重装、重新配对或并发写入追平/超过后端，配置将永远不会再被应用。
- 修复：判断条件改为「revision 变大 **或** 配置内容发生变化」，新增 `RemoteRelayStore.configHash` 记录最近应用过的配置指纹；`stableConfigHash()` 递归按键名排序规范化 JSON，避免服务端往返导致键顺序变化而反复应用。
- 配对时记录初始快照指纹，首次同步不会重复应用一次配置。
- 新增日志：应用配置时输出 `revision`、`revisionAdvanced`、`contentChanged`，便于定位。
- 配套：小程序 `pages/config/config.js` 补上 `weekTypeIndex` 回填（原 `load()` 从未回填，导致大小周「本周大周」保存后回显为小周），判定规则与 `WorkdayScheduleResolver` 一致（锚点周为大周，按周差奇偶交替）。
- 验证：`:app:compileDebugKotlin`、`testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug` 全部通过。

### 2026-10-13 修复远程打卡结束后不返回本应用
- 现象：小程序【立即打卡】能打开钉钉并弹出倒计时悬浮窗，30 秒超时后悬浮窗消失，但停留在钉钉，不回到 DailyTask。
- 根因：流程结束后的返回动作依赖 `TaskScheduler._returnToApp` 事件，而该事件只有 `MainActivity` 前台存活时才会被 `lifecycleScope` 收集；远程指令触发时 MainActivity 可能不在前台，事件被丢弃。
- 修复：`waitForClockIn()` 的 `finally` 中新增 `returnToDailyTask()`，直接从应用上下文以 `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_REORDER_TO_FRONT or FLAG_ACTIVITY_SINGLE_TOP` 拉起 `MainActivity`，不依赖 MainActivity 存活。
- 该修复同时作用于定时任务与远程指令两条路径，保证「打开目标应用 → 等待打卡或超时 → 返回本应用」流程完全一致。
- 新增日志：切换成功记录「打卡流程结束，已切回本应用」，失败记录具体异常信息。
- 验证：JDK 17 下 `:app:compileDebugKotlin`、`testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug` 全部通过。

### 2026-10-13 修复手动启动任务显示「今日任务已全部执行完毕」
- 现象：设置 18:00 / 19:00 任务后手动开启，立即显示「今日任务已全部执行完毕」，任务不执行。
- 根因：`TaskScheduler.lastProcessedDate` 为进程内字段，一旦当天被标记为「已处理」（例如 `checkMissedReset` 自动启动过一次），后续手动启动会直接进入「今日已处理，等待下一次重置」分支，仅显示完成提示而不重新执行排程。休息日判定（`shouldSkipToday()`）同样会一次性标记当天已处理。
- 修复：`startTask(force: Boolean = false)` 新增 `force` 参数，手动启动路径传 `true`，会取消旧调度协程、清除 `lastProcessedDate`、并跳过休息日判定，强制重新构建并执行当天排程。
- 调用点：首页启动按钮（`MainActivity` L294）、聊天指令「DT#执行任务」（L348）、小程序远程 `START_TASK`（`RemoteCommandProcessor`）改用 `force = true`；自动路径（`ForegroundRunningService` 每日重置、`checkMissedReset`）保持 `force = false`。
- 诊断增强：`startTask` 记录是否强制重启；休息日跳过时记录 `dayFlag`；排程构建时记录每个任务的「计划时间/实际时间」，便于用 `app_runtime_log.txt` 定位。
- 验证：JDK 17 下 `:app:compileDebugKotlin`、`testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug` 全部通过。

### 2026-10-13 代码审查修复：打卡信号并发与取消安全
- 审查发现上一提交存在的缺陷并全部修复。
- **打卡信号并发覆盖**：`TaskScheduler` 原用对象级 `clockInDeferred` 承载等待，定时任务与远程打卡并发时会互相覆盖导致信号丢失。改为 `Channel<Unit>(Channel.CONFLATED)`，`waitForClockIn` 用 `clockInSignal.onReceive` 参与 `select`，`notifyClockIn()` 改 `trySend`。
- **取消路径泄漏**：原实现仅在 `select` 正常返回后清理 `clockInDeferred`，被 `withTimeoutOrNull` 取消时会残留。现用 `try/finally` 统一清理，且等待前 `tryReceive()` 清空陈旧信号，避免上一次通知误判为本次成功。
- **脱离调用方作用域**：倒计时任务原用 `CoroutineScope(Dispatchers.IO)` 独立作用域，取消不生效。改为 `coroutineScope { launch { ... } }` 子协程，调用方取消时一并取消。
- **主线程回归**：`openApplication()` 原在 `Dispatchers.IO` 上执行（重构前由 `withContext(Dispatchers.Main)` 包裹），现恢复 `withContext(Dispatchers.Main)`。
- **截屏失败通知丢失**：补回 `else` 分支，截屏失败时发送文本通知，避免整条超时路径静默。
- **超时状态误报**：远程打卡超时由 `SUCCEEDED` 改为 `FAILED`，避免小程序列表显示绿色成功。
- 验证：JDK 17 下 `:app:compileDebugKotlin`、`testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug` 全部通过。

### 2026-10-13 修复旧机型远程指令拉取与远程打卡流程
- 问题：OPPO A3（ColorOS）会把前台服务杀掉，导致 `RemoteRelayCoordinator` 的 60 秒轮询停止；只剩 WorkManager 15 分钟周期任务，实际可能几十分钟才拉取一次指令，小程序端指令长期停留在 `PENDING`。
- 新增 `RelaySyncAlarmReceiver` + `RemoteSyncScheduler` 的 `AlarmManager` 兜底（每 15 分钟，`setExactAndAllowWhileIdle`，无精确闹钟权限时回退 `setAndAllowWhileIdle`），唤醒后串起下一次闹钟并入队一次性 Worker。Manifest 新增 `USE_EXACT_ALARM` 权限与接收器声明；配对成功时注册、解绑时取消。
- `TaskScheduler` 抽出 `waitForClockIn(timeoutSeconds, captureOnTimeout)`，定时任务与远程「立即打卡」共用「打开目标应用 → 等待打卡通知或超时」流程；定时任务保留兜底截屏，远程指令不触发截屏。
- `RemoteCommandProcessor` 的 `TRIGGER_CLOCK_IN` 不再「打开应用即返回成功」，改为等待真实结果；`START_TASK` / `STOP_TASK` 补齐 `MessageDispatcher` 通知，与 `DT#` 聊天指令一致；`QUERY_STATUS` 返回真实运行状态。
- 打卡成功信号改由 `NotificationMonitorService` 直接调用 `TaskScheduler.notifyClockIn()`，不再依赖 `MainActivity` 存活；`MainActivity` 仅负责界面返回。
- `RemoteRelayCoordinator` 将指令执行移出同步互斥锁并加入 `inFlightCommands` 去重，避免远程打卡等待期间阻塞后续轮询；ACK 失败时暂存结果下次补发，不重复执行指令；兼容后端返回的 `id` 与 `commandId` 字段。
- 小程序 `device.wxml` 展示指令 `resultMessage`，可看到「打卡成功」或超时原因。
- 验证：JDK 17 下 `:app:compileDebugKotlin`、`testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug` 全部通过；旧机型真实拉取、远程打卡与通知行为需真机回归。

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

### 2026-10-09 远程中继地址校验放宽并拦截 localhost
- `RemoteRelayActivity.kt` 的中继地址校验由“必须 https://”放宽为 `http://` 或 `https://`，便于本地联调 Spring Boot 后端。
- 新增 host 校验：`localhost`、`127.0.0.1` 直接提示“指向手机自身”，避免真机误配。
- README「远程中继」补充本地联调说明：真机必须填写电脑局域网 IP（如 `http://192.168.x.x:8080/jeecg-boot/relay`）。
- 验证方式：`:app:compileDebugKotlin`、`:app:assembleDebug` 通过（JDK 17）。
