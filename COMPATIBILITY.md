# 兼容性记录

## 已实现的基线

| 项目 | 当前值 |
|---|---|
| Android | API 31 起（Android 12） |
| compile / target | 36 / 36 |
| Gradle / AGP | 8.11.1 / 8.9.2 |
| Kotlin | 2.1.20 |
| Shizuku | API / provider 13.1.5 |
| 权限模型 | 无 Root；依赖用户授予的 Shizuku 权限和悬浮窗权限 |
| 无障碍 | 未声明 `BIND_ACCESSIBILITY_SERVICE`，不依赖无障碍 |

## 设备验证状态

容器中没有连接实际 Android 设备，因此目前完成的是编译、单元测试和 Android 测试源码编译；Samsung Galaxy S23 Ultra / One UI 的真实 dump 字段仍需在 Phase 0 运行后填写。不要把当前文本正则当成已经通过三星实机验证。

Phase 0 要分别在键盘隐藏、键盘显示、横屏和不同应用输入框中采样，并记录诊断页中的：

- `mInputShown` / `inputShown`
- `mImeWindowVis`
- `mCurMethodId` / `mCurId`
- `mShowRequested`
- 解析状态、dump 大小和耗时

## 解析策略

| 优先级 | 字段 | 行为 |
|---|---|---|
| 1 | `mInputShown` / `inputShown` | 使用最后一个明确布尔值；冲突时保留错误诊断 |
| 2 | `mImeWindowVis` | 按 `value & 0x2` 判断 IME_VISIBLE，支持十进制和十六进制 |
| 3 | 两者都缺失 | 返回 `UNKNOWN`，不当成 `HIDDEN` |

`mShowRequested` 只用于诊断。多用户 dump 会优先尝试匹配当前用户段；找不到时保留全量文本解析，便于诊断 ROM 变体。

## 已知风险和降级

- `ServiceManager.getService("input_method")` 是隐藏 API，通过反射访问；系统升级后可能限制或改变行为。
- 不同 ROM 可能不输出 `CRITICAL` 文本中的可见性字段；此时自动显隐进入未知/错误路径，手动常显仍可用。
- Android 11+ 的包可见性可能让普通应用漏掉第三方输入法；应用声明输入法服务查询，并把 `InputMethodManager` 列表与只读的 `Settings.Secure.ENABLED_INPUT_METHODS` 合并。
- Shizuku 未运行、授权撤回、UserService 断开或 Binder dump 超时都会隐藏按钮并降低采样频率。三星 dump 超过采样上限时只保留有界前缀和关键行，不把整次采样判成失败。
- 目标输入法必须来自系统返回的“已启用输入法”列表；被禁用或卸载时不会执行切换命令。
- Android 14+ 使用 `specialUse` 前台服务类型；通知权限未授予时系统通知可见性会受系统策略影响。
- Samsung One UI 可能调整 `TYPE_APPLICATION_OVERLAY` 与 IME 的层级，按钮位置需要实机校准。
- 监测窗口会校验 View 是否仍附着在 WindowManager；窗口被系统移除、窗口参数失效或恢复位置超出当前屏幕时，会自动重建并把位置限制在屏幕范围内。

## 建议验收矩阵

微信、Telegram、Chrome 网页文本框、Samsung Notes、系统设置搜索框、密码框、横屏、分屏、快速切应用、Home、锁屏/解锁、Shizuku 停止/恢复、撤销悬浮窗权限、禁用目标输入法，以及 Gboard 与豆包双向切换。
