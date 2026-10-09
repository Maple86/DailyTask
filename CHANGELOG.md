# 版本更新日志

> 提交范围: `66c2177c` → `02c9766` | [返回 README](README.md)

## 🐛 修复 (Bug Fixes)

### 远程指令

- **修复远程指令解析未识别折叠通知的问题** — 原实现要求指令必须以 `DT#` 开头，但微信多条消息会折叠合并成一条通知（正文形如 `[4条]昵称: DT#执行任务`），导致 `startsWith` 判断失败、指令被静默丢弃。现改为查找 `DT#` 前缀位置并截取其后的指令正文，兼容折叠通知格式。

---

## 🔧 优化 (Improvements)

### UI 与样式

- **更新设置页图标控件及文案** — 目标应用图标由 `ShapeableImageView` + `RoundedStyle` 改为 `ImageFilterView` + `roundPercent`，并精简"返回截图"开关的标题与说明文案
- **移除未使用的 `RoundedStyle` 样式** — 删除 `styles.xml` 及不再引用的圆角样式
- **移除免费软件相关提示** — 删除仅用于生成水印文案的 native 模块及 `WatermarkDrawable`，首页与设置页不再叠加背景水印；同时移除设置入口的“使用须知”弹窗，点击设置图标直接进入设置页

### 代码清理

- **调整配置常量格式** — 格式化 `Constant.kt` 中 `REMOTE_CLOCK_IN_CAPTURE_KEY` 常量定义
