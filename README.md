# 输入法悬浮切换

这是一个 Kotlin Android 小工具：在软键盘显示时显示一个可拖动悬浮按钮，在两个“已启用输入法”之间切换。实现不需要 Root，也不启用无障碍服务；需要 Shizuku 提供一个长生命周期 UserService。

## 当前实现

- `minSdk 31`、`compileSdk 36`、`targetSdk 36`
- 单 Activity + `ImeWatcherService` 前台服务
- Shizuku UserService 直接取得 `input_method` Binder，并调用 `binder.dump(..., ["--dump-priority", "CRITICAL"])`
- 文本解析优先读取 `mInputShown/inputShown`，否则读取 `mImeWindowVis`
- `UNKNOWN` 不会被误判为隐藏；包含 2 秒未知保持、隐藏双采样防抖和切换保护窗口
- 使用 `InputMethodManager.getEnabledInputMethodList()` 选择输入法，切换命令使用参数数组，不经过 `sh -c`
- 每次从输入法切走前记录其当前 subtype；再次切回时恢复上次使用的语言或布局，已被禁用的 subtype 会自动跳过
- 为兼容 Android 11+ 包可见性和部分 One UI 行为，已启用列表会与 `Settings.Secure.ENABLED_INPUT_METHODS` 做只读合并
- 监测悬浮窗会检查实际 WindowManager 附着状态；窗口失效或位置越界时自动重建
- 只在内存保留最近 50 次采样的脱敏统计；Phase 0 按钮显示有限长度的关键行，不保存原始 dump
- 未实现 proto 解析：先用真实设备样本确认 One UI 字段，再决定是否增加

## 编译

用 Android Studio 打开项目根目录，使用 JDK 17 和 Android SDK 36。命令行构建需要 Gradle 8.11.1、Android SDK Platform 36 和 Build Tools 36：

```bash
gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

APK 输出在 `app/build/outputs/apk/debug/app-debug.apk`。安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 首次使用（无 Root）

1. 安装并启动 Shizuku，使用 Shizuku 提供的无线调试/ADB 启动方式。
2. 启动本应用，点击“请求 Shizuku 授权”，确认授权后检查 UID；无 Root 的 ADB 模式通常显示 `2000`。
3. 授予悬浮窗权限和通知权限。
4. 确认 Gboard、豆包或其他目标输入法已经在系统设置中启用，在本应用选择 Primary/Secondary 并保存。应用会在第一次切走时记住当前语言，以后自动恢复。
5. 先点击“测试读取 IME 状态（Phase 0 探针）”：分别在键盘隐藏和显示时采样，查看 `SHOWN/HIDDEN/UNKNOWN`、字段值和关键行。
6. 点击“测试悬浮按钮”确认位置；再点击“启动监测”。

如果 Shizuku 没启动、权限被撤回或 Binder 断开，应用会隐藏按钮并在通知中提示，不会启动 shell 轮询，也不会崩溃。解析器不兼容时可以打开“解析不兼容时手动常显”，但自动显隐会被关闭。

## 设计边界

应用没有全局 IME 可见回调，因此采用 Shizuku UserService 中的 Binder CRITICAL dump 采样。普通应用没有可靠的跨应用 `WindowInsets` 监听能力；本项目也不使用截图、OCR、坐标点击、编辑框文本、剪贴板、通知、麦克风或前台应用识别。

三星 One UI 可能把普通应用悬浮窗放到输入法窗口下方。按钮默认在右侧应用内容区域附近，支持长按拖动；实际设备验证前不承诺它能覆盖在键盘按键之上。

更多兼容性和隐私说明见 [COMPATIBILITY.md](COMPATIBILITY.md) 与 [PRIVACY.md](PRIVACY.md)。
