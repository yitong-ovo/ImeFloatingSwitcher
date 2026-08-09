package ng.yito.imefloatingswitcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import ng.yito.imefloatingswitcher.diagnostics.DiagnosticsFormatter
import ng.yito.imefloatingswitcher.diagnostics.DiagnosticsRingBuffer
import ng.yito.imefloatingswitcher.ime.EnabledImeRepository
import ng.yito.imefloatingswitcher.ime.ImeInfo
import ng.yito.imefloatingswitcher.ime.InputMethodSubtypeLabelResolver
import ng.yito.imefloatingswitcher.ime.currentImeId
import ng.yito.imefloatingswitcher.model.ImeSnapshot
import ng.yito.imefloatingswitcher.overlay.FloatingImeButtonView
import ng.yito.imefloatingswitcher.overlay.OverlayController
import ng.yito.imefloatingswitcher.settings.AppSettings
import ng.yito.imefloatingswitcher.settings.AppSettingsRepository
import ng.yito.imefloatingswitcher.settings.MAX_OVERLAY_BUTTONS
import ng.yito.imefloatingswitcher.shizuku.ShizukuGateway
import ng.yito.imefloatingswitcher.watcher.ImeWatcherService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {
    private lateinit var scrollView: ScrollView
    private lateinit var statusCard: MaterialCardView
    private lateinit var statusChip: Chip
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var primaryActionButton: MaterialButton
    private lateinit var secondaryActionButton: MaterialButton
    private lateinit var shizukuStepStatus: TextView
    private lateinit var overlayStepStatus: TextView
    private lateinit var notificationStepStatus: TextView
    private lateinit var imeStepStatus: TextView
    private lateinit var inputMethodSection: LinearLayout
    private lateinit var primaryImeLayout: TextInputLayout
    private lateinit var secondaryImeLayout: TextInputLayout
    private lateinit var primaryImeField: MaterialAutoCompleteTextView
    private lateinit var secondaryImeField: MaterialAutoCompleteTextView
    private lateinit var imeSelectionHint: TextView
    private lateinit var manualAlwaysVisible: MaterialSwitch
    private lateinit var buttonCountGroup: MaterialButtonToggleGroup
    private val buttonCountIds = IntArray(MAX_OVERLAY_BUTTONS)
    private val buttonSizeSections = mutableListOf<LinearLayout>()
    private val buttonSizeLabels = mutableListOf<TextView>()
    private val buttonSizeSeekBars = mutableListOf<SeekBar>()
    private val buttonSizePreviews = mutableListOf<FloatingImeButtonView>()
    private lateinit var advancedContent: LinearLayout
    private lateinit var advancedToggle: TextView
    private lateinit var technicalStatusText: TextView
    private lateinit var diagnosticsText: TextView
    private lateinit var enabledImeRepository: EnabledImeRepository
    private lateinit var settingsRepository: AppSettingsRepository
    private lateinit var subtypeLabelResolver: InputMethodSubtypeLabelResolver
    private lateinit var gateway: ShizukuGateway
    private lateinit var testOverlayController: OverlayController
    private val diagnostics = DiagnosticsRingBuffer()
    private var enabledImes: List<ImeInfo> = emptyList()
    private var primaryImeIndex = -1
    private var secondaryImeIndex = -1
    private var appSettings = AppSettings()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshStatus() }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refreshStatus() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { refreshStatus() }
    private val permissionResultListener = object : Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
            runOnUiThread { refreshStatus() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val lightTheme = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK !=
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightTheme
            isAppearanceLightNavigationBars = lightTheme
        }
        enabledImeRepository = EnabledImeRepository(this)
        settingsRepository = AppSettingsRepository(this)
        subtypeLabelResolver = InputMethodSubtypeLabelResolver(this)
        gateway = ShizukuGateway(this) { runOnUiThread { refreshStatus() } }
        testOverlayController = OverlayController(
            context = this,
            savePosition = { index, x, y ->
                lifecycleScope.launch { settingsRepository.savePosition(index, x, y) }
            },
            onClick = { toast("测试按钮可正常点击") }
        )
        setContentView(buildContent())
        lifecycleScope.launch {
            settingsRepository.flow.collectLatest {
                val imeSelectionChanged = appSettings.primaryImeId != it.primaryImeId ||
                    appSettings.secondaryImeId != it.secondaryImeId
                appSettings = it
                if (manualAlwaysVisible.isChecked != it.manualAlwaysVisible) {
                    manualAlwaysVisible.isChecked = it.manualAlwaysVisible
                }
                syncOverlayButtonControls(it)
                if (imeSelectionChanged) selectSavedImeIds(it)
                refreshStatus()
            }
        }
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        refreshEnabledImes()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshEnabledImes()
        refreshStatus()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        gateway.unbind()
        testOverlayController.hide()
        super.onDestroy()
    }

    private fun buildContent(): View {
        scrollView = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = true
            setBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurface))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }
        ViewCompat.setOnApplyWindowInsetsListener(scrollView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }
        scrollView.addView(root, matchParent())

        root.addView(TextView(this).apply {
            text = "输入法悬浮切换"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall)
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
        })
        root.addView(bodyText("键盘弹起时，用悬浮按钮快速切换两个输入法。").apply {
            setPadding(0, dp(6), 0, dp(18))
        })

        root.addView(buildStatusCard(), withTopMargin(0))

        root.addView(sectionTitle("准备事项"))
        root.addView(bodyText("按提示完成设置，状态会自动更新。").apply {
            setPadding(0, 0, 0, dp(4))
        })
        shizukuStepStatus = addCheckRow(root, "Shizuku")
        overlayStepStatus = addCheckRow(root, "悬浮窗权限")
        notificationStepStatus = addCheckRow(root, "通知权限")
        imeStepStatus = addCheckRow(root, "可用输入法")

        inputMethodSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inputMethodSection.addView(sectionTitle("切换设置"))
        inputMethodSection.addView(bodyText("选择点击悬浮按钮时互相切换的两个输入法；切换时会恢复各自上次使用的语言。").apply {
            setPadding(0, 0, 0, dp(12))
        })
        val primaryDropdown = imeDropdown("主输入法")
        primaryImeLayout = primaryDropdown.first
        primaryImeField = primaryDropdown.second
        inputMethodSection.addView(primaryImeLayout, matchParent())
        val secondaryDropdown = imeDropdown("辅助输入法")
        secondaryImeLayout = secondaryDropdown.first
        secondaryImeField = secondaryDropdown.second
        inputMethodSection.addView(secondaryImeLayout, withTopMargin(12))
        imeSelectionHint = bodyText("").apply { setPadding(0, dp(8), 0, dp(8)) }
        inputMethodSection.addView(imeSelectionHint, matchParent())
        inputMethodSection.addView(TextView(this).apply {
            text = "悬浮按钮数量"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
            setPadding(0, dp(12), 0, 0)
        }, matchParent())
        buttonCountGroup = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val buttonCountLabels = resources.getStringArray(R.array.overlay_button_counts)
        repeat(MAX_OVERLAY_BUTTONS) { index ->
            buttonCountIds[index] = View.generateViewId()
            buttonCountGroup.addView(
                MaterialButton(
                    this@MainActivity,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    id = buttonCountIds[index]
                    text = buttonCountLabels[index]
                    isCheckable = true
                    isAllCaps = false
                    minWidth = 0
                    minHeight = dp(48)
                    contentDescription = "显示${index + 1}个悬浮按钮"
                },
                LinearLayout.LayoutParams(0, dp(48), 1f)
            )
        }
        buttonCountGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val count = buttonCountIds.indexOf(checkedId) + 1
            if (count > 0 && count != appSettings.overlayButtonCount) {
                lifecycleScope.launch { settingsRepository.setOverlayButtonCount(count) }
            }
        }
        inputMethodSection.addView(buttonCountGroup, withTopMargin(8))
        inputMethodSection.addView(bodyText("多个按钮功能相同，可分别拖到顺手的位置。").apply {
            setPadding(0, dp(8), 0, 0)
        }, matchParent())
        repeat(MAX_OVERLAY_BUTTONS) { index ->
            inputMethodSection.addView(buildButtonSizeSection(index), matchParent())
        }
        root.addView(inputMethodSection, matchParent())

        advancedToggle = TextView(this).apply {
            text = "高级设置与诊断  展开"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            setTextColor(themeColor(com.google.android.material.R.attr.colorPrimary))
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(56)
            isClickable = true
            isFocusable = true
            val selectableBackground = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, selectableBackground, true)
            setBackgroundResource(selectableBackground.resourceId)
            stateDescription = "已折叠"
            setOnClickListener { setAdvancedExpanded(advancedContent.visibility != View.VISIBLE) }
        }
        root.addView(advancedToggle, withTopMargin(12))

        advancedContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        manualAlwaysVisible = MaterialSwitch(this).apply {
            text = "键盘识别失败时保持按钮显示"
            minHeight = dp(48)
            setOnCheckedChangeListener { _, checked ->
                if (checked != appSettings.manualAlwaysVisible) {
                    lifecycleScope.launch { settingsRepository.setManualAlwaysVisible(checked) }
                }
            }
        }
        advancedContent.addView(manualAlwaysVisible, matchParent())
        advancedContent.addView(bodyText("仅在悬浮按钮不能随键盘正常显示或隐藏时开启。").apply {
            setPadding(dp(12), 0, 0, dp(8))
        })
        advancedContent.addView(outlinedButton("测试悬浮按钮（5 秒）") {
            if (Settings.canDrawOverlays(this)) {
                testOverlayController.showTest(appSettings)
                lifecycleScope.launch {
                    delay(5_000L)
                    testOverlayController.hide()
                }
            } else {
                toast("请先授予悬浮窗权限")
            }
        }, withTopMargin(8))
        advancedContent.addView(outlinedButton("读取诊断信息") { testImeSnapshot() }, withTopMargin(8))
        advancedContent.addView(smallLabel("技术状态"), withTopMargin(18))
        technicalStatusText = diagnosticPanel("")
        advancedContent.addView(technicalStatusText, withTopMargin(8))
        advancedContent.addView(smallLabel("最近一次诊断"), withTopMargin(18))
        diagnosticsText = diagnosticPanel("尚未读取诊断信息")
        advancedContent.addView(diagnosticsText, withTopMargin(8))
        advancedContent.addView(bodyText("不会读取输入内容、剪贴板、通知内容、麦克风或前台应用内容。").apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setPadding(0, dp(12), 0, 0)
        })
        root.addView(advancedContent, matchParent())

        configureImeSelectionListeners()
        syncOverlayButtonControls(appSettings)
        return scrollView
    }

    private fun buildStatusCard(): MaterialCardView {
        statusCard = MaterialCardView(this).apply {
            radius = dp(8).toFloat()
            strokeWidth = 0
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        statusChip = Chip(this).apply {
            text = "检查中"
            isClickable = false
            isCheckable = false
            setEnsureMinTouchTargetSize(false)
        }
        content.addView(statusChip, wrapContent())
        statusTitle = TextView(this).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
            setPadding(0, dp(12), 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(statusTitle, matchParent())
        statusDetail = bodyText("").apply {
            setPadding(0, dp(6), 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(statusDetail, matchParent())
        primaryActionButton = MaterialButton(this).apply {
            isAllCaps = false
            minHeight = dp(52)
        }
        content.addView(primaryActionButton, withTopMargin(18))
        secondaryActionButton = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "暂不，直接启动"
            isAllCaps = false
            minHeight = dp(48)
            visibility = View.GONE
            setOnClickListener { startMonitoring() }
        }
        content.addView(secondaryActionButton, withTopMargin(8))
        statusCard.addView(content, matchParent())
        return statusCard
    }

    private fun configureImeSelectionListeners() {
        primaryImeField.setOnItemClickListener { _, _, position, _ ->
            primaryImeIndex = position
            onImeSelectionChanged()
        }
        secondaryImeField.setOnItemClickListener { _, _, position, _ ->
            secondaryImeIndex = position
            onImeSelectionChanged()
        }
    }

    private fun onImeSelectionChanged() {
        updateImeSelectionFeedback()
        refreshStatus()
        val primary = selectedImeId(primaryImeIndex)
        val secondary = selectedImeId(secondaryImeIndex)
        if (primary != null && secondary != null && primary != secondary) {
            lifecycleScope.launch { settingsRepository.saveImeSelection(primary, secondary) }
        }
    }

    private fun refreshEnabledImes() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { enabledImeRepository.getEnabled() }
            enabledImes = items
            val adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_dropdown_item_1line,
                items.map { it.label }
            )
            primaryImeField.setAdapter(adapter)
            secondaryImeField.setAdapter(adapter)
            primaryImeField.isEnabled = items.isNotEmpty()
            secondaryImeField.isEnabled = items.isNotEmpty()
            selectSavedImeIds(appSettings)
            refreshStatus()
        }
    }

    private fun selectSavedImeIds(settings: AppSettings) {
        if (!::primaryImeField.isInitialized) return
        if (enabledImes.isEmpty()) {
            primaryImeIndex = -1
            secondaryImeIndex = -1
            primaryImeField.setText("", false)
            secondaryImeField.setText("", false)
            updateImeSelectionFeedback()
            return
        }
        val suggestions = enabledImeRepository.suggestions(enabledImes)
        primaryImeIndex = enabledImes.indexOfFirst { it.id == settings.primaryImeId }
            .takeIf { it >= 0 }
            ?: suggestions.first?.let { suggestion -> enabledImes.indexOfFirst { it.id == suggestion.id } }
            ?: -1
        secondaryImeIndex = enabledImes.indexOfFirst { it.id == settings.secondaryImeId }
            .takeIf { it >= 0 }
            ?: suggestions.second?.let { suggestion -> enabledImes.indexOfFirst { it.id == suggestion.id } }
            ?: -1
        primaryImeField.setText(enabledImes.getOrNull(primaryImeIndex)?.label.orEmpty(), false)
        secondaryImeField.setText(enabledImes.getOrNull(secondaryImeIndex)?.label.orEmpty(), false)
        updateImeSelectionFeedback()
    }

    private fun updateImeSelectionFeedback() {
        if (!::imeSelectionHint.isInitialized) return
        val primary = selectedImeId(primaryImeIndex)
        val secondary = selectedImeId(secondaryImeIndex)
        val message = when {
            enabledImes.size < 2 -> "至少需要启用两个输入法。"
            primary == null || secondary == null -> "请选择两个输入法。"
            primary == secondary -> "主输入法和辅助输入法不能相同。"
            else -> buildString {
                append("选择会自动保存，并记住每个输入法上次使用的语言。")
                listOfNotNull(
                    rememberedSubtypeHint(primary),
                    rememberedSubtypeHint(secondary)
                ).takeIf { it.isNotEmpty() }?.let {
                    append('\n')
                    append(it.joinToString("；"))
                }
            }
        }
        val error = primary == secondary && primary != null || enabledImes.size < 2
        imeSelectionHint.text = message
        imeSelectionHint.setTextColor(
            themeColor(
                if (error) com.google.android.material.R.attr.colorError
                else com.google.android.material.R.attr.colorOnSurfaceVariant
            )
        )
        secondaryImeLayout.error = if (primary == secondary && primary != null) message else null
    }

    private fun rememberedSubtypeHint(imeId: String): String? {
        val subtype = subtypeLabelResolver.resolve(imeId, appSettings.rememberedSubtypeHash(imeId))
            ?: return null
        val imeLabel = enabledImes.firstOrNull { it.id == imeId }?.label ?: return null
        return if (subtype.shortLabel == "?") {
            "$imeLabel：尚未记忆语言"
        } else {
            "$imeLabel 已记忆：${subtype.fullLabel}"
        }
    }

    private fun testImeSnapshot() {
        lifecycleScope.launch {
            if (!gateway.isBinderAvailable()) {
                diagnosticsText.text = "Shizuku 未运行"
                return@launch
            }
            if (!gateway.isPermissionGranted()) {
                diagnosticsText.text = "Shizuku 未授权"
                return@launch
            }
            gateway.bind()
            val connected = withTimeoutOrNull(3_000L) {
                while (!gateway.isConnected) delay(100L)
                true
            } == true
            if (!connected) {
                diagnosticsText.text = "Shizuku 服务未连接，请重新授权后再试。"
                return@launch
            }
            val snapshot: ImeSnapshot = gateway.readImeSnapshot()
            diagnostics.add(snapshot)
            diagnosticsText.text = DiagnosticsFormatter.format(
                snapshot,
                publicImeId = withContext(Dispatchers.IO) {
                    currentImeId()
                },
                primaryId = appSettings.primaryImeId,
                secondaryId = appSettings.secondaryImeId,
                ring = diagnostics,
                deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.FINGERPRINT}",
                shizukuInfo = "API ${runCatching { Shizuku.getVersion() }.getOrDefault(-1)}, UID ${gateway.uidOrNull() ?: "未知"}, Binder ${if (gateway.isBinderAvailable()) "alive" else "dead"}",
                overlayInfo = if (testOverlayController.isShown()) "测试按钮已显示" else if (Settings.canDrawOverlays(this@MainActivity)) "权限已授权，测试按钮未显示" else "权限未授权"
            )
        }
    }

    private fun startMonitoring() {
        lifecycleScope.launch {
            val primary = selectedImeId(primaryImeIndex)
            val secondary = selectedImeId(secondaryImeIndex)
            if (primary == null || secondary == null || primary == secondary) {
                focusImeSelection()
                return@launch
            }
            settingsRepository.saveImeSelection(primary, secondary)
            if (!Settings.canDrawOverlays(this@MainActivity)) {
                openOverlaySettings()
                return@launch
            }
            if (!gateway.isBinderAvailable() || !gateway.isPermissionGranted()) {
                toast("请先启动并授权 Shizuku")
                return@launch
            }
            val intent = Intent(this@MainActivity, ImeWatcherService::class.java)
                .setAction(ImeWatcherService.ACTION_START)
            ContextCompat.startForegroundService(this@MainActivity, intent)
            toast("悬浮切换已启动")
            delay(200L)
            refreshStatus()
        }
    }

    private fun stopMonitoring() {
        stopService(Intent(this, ImeWatcherService::class.java))
        toast("悬浮切换已停止")
        lifecycleScope.launch {
            delay(100L)
            refreshStatus()
        }
    }

    private fun refreshStatus() {
        if (!::statusTitle.isInitialized) return
        val overlayGranted = Settings.canDrawOverlays(this)
        val notificationGranted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val binderAvailable = gateway.isBinderAvailable()
        val shizukuGranted = binderAvailable && gateway.isPermissionGranted()
        val validSelection = selectedImeId(primaryImeIndex)?.let { primary ->
            selectedImeId(secondaryImeIndex)?.let { secondary -> primary != secondary }
        } == true
        val monitoring = ImeWatcherService.isRunning

        setStepStatus(
            shizukuStepStatus,
            when {
                !binderAvailable -> "未运行"
                !shizukuGranted -> "待授权"
                else -> "已授权"
            },
            shizukuGranted
        )
        setStepStatus(overlayStepStatus, if (overlayGranted) "已允许" else "待授权", overlayGranted)
        setStepStatus(
            notificationStepStatus,
            if (notificationGranted) "已允许" else "建议允许",
            notificationGranted,
            optional = !notificationGranted
        )
        setStepStatus(
            imeStepStatus,
            if (enabledImes.size >= 2) "${enabledImes.size} 个可用" else "需要至少 2 个",
            enabledImes.size >= 2
        )
        updateImeSelectionFeedback()
        updateTechnicalStatus(overlayGranted, notificationGranted, binderAvailable, shizukuGranted)

        secondaryActionButton.visibility = View.GONE
        when {
            monitoring -> {
                val problem = when {
                    !binderAvailable -> "Shizuku 已停止"
                    !shizukuGranted -> "Shizuku 授权已失效"
                    !overlayGranted -> "悬浮窗权限已关闭"
                    !validSelection -> "输入法设置已失效"
                    else -> null
                }
                if (problem == null) {
                    showHero(
                        chip = "运行中",
                        title = "悬浮切换正在运行",
                        detail = "键盘弹起时会显示悬浮按钮。",
                        action = "停止运行",
                        containerColor = com.google.android.material.R.attr.colorSecondaryContainer,
                        contentColor = com.google.android.material.R.attr.colorOnSecondaryContainer,
                        onClick = ::stopMonitoring
                    )
                } else {
                    showHero(
                        chip = "需要处理",
                        title = "悬浮切换暂不可用",
                        detail = "$problem。停止后会引导你修复。",
                        action = "停止并修复",
                        containerColor = com.google.android.material.R.attr.colorErrorContainer,
                        contentColor = com.google.android.material.R.attr.colorOnErrorContainer,
                        onClick = ::stopMonitoring
                    )
                }
            }

            !binderAvailable -> showHero(
                chip = "第 1 步",
                title = "先启动 Shizuku",
                detail = "启动后返回这里，状态会自动更新。",
                action = "打开 Shizuku",
                onClick = ::openShizuku
            )

            !shizukuGranted -> showHero(
                chip = "第 1 步",
                title = "授权 Shizuku",
                detail = "仅用于识别键盘状态和切换输入法。",
                action = "授权 Shizuku",
                onClick = { gateway.requestPermission(SHIZUKU_REQUEST_CODE) }
            )

            !overlayGranted -> showHero(
                chip = "第 2 步",
                title = "允许显示悬浮按钮",
                detail = "接下来会打开系统的悬浮窗设置。",
                action = "去授权",
                onClick = ::openOverlaySettings
            )

            enabledImes.size < 2 -> showHero(
                chip = "第 3 步",
                title = "还需要一个输入法",
                detail = "请先在系统设置中启用至少两个输入法。",
                action = "打开输入法设置",
                onClick = { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
            )

            !validSelection -> showHero(
                chip = "第 3 步",
                title = "选择两个输入法",
                detail = "主输入法和辅助输入法需要不同。",
                action = "去选择",
                onClick = ::focusImeSelection
            )

            !notificationGranted -> {
                showHero(
                    chip = "最后一步",
                    title = "准备完成",
                    detail = "允许通知后，可以随时暂停或停止监测。",
                    action = "允许通知",
                    onClick = {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                )
                secondaryActionButton.visibility = View.VISIBLE
            }

            else -> showHero(
                chip = "已就绪",
                title = "可以开始了",
                detail = "启动后，悬浮按钮只会在键盘显示时出现。",
                action = "启动悬浮切换",
                containerColor = com.google.android.material.R.attr.colorPrimaryContainer,
                contentColor = com.google.android.material.R.attr.colorOnPrimaryContainer,
                onClick = ::startMonitoring
            )
        }
    }

    private fun showHero(
        chip: String,
        title: String,
        detail: String,
        action: String,
        containerColor: Int = com.google.android.material.R.attr.colorSurfaceContainerHigh,
        contentColor: Int = com.google.android.material.R.attr.colorOnSurface,
        onClick: () -> Unit
    ) {
        val foreground = themeColor(contentColor)
        statusCard.setCardBackgroundColor(themeColor(containerColor))
        statusChip.text = chip
        statusChip.chipBackgroundColor = ColorStateList.valueOf(
            MaterialColors.layer(themeColor(containerColor), foreground, 0.12f)
        )
        statusChip.setTextColor(foreground)
        statusTitle.text = title
        statusTitle.setTextColor(foreground)
        statusDetail.text = detail
        statusDetail.setTextColor(foreground)
        primaryActionButton.text = action
        primaryActionButton.setOnClickListener { onClick() }
    }

    private fun updateTechnicalStatus(
        overlayGranted: Boolean,
        notificationGranted: Boolean,
        binderAvailable: Boolean,
        shizukuGranted: Boolean
    ) {
        technicalStatusText.text = buildString {
            appendLine("Shizuku Binder：${if (binderAvailable) "可用" else "不可用"}")
            appendLine("Shizuku API：${runCatching { Shizuku.getVersion() }.getOrDefault(-1)}")
            appendLine("Shizuku 授权：${if (shizukuGranted) "已授权" else "未授权"}")
            appendLine("Shizuku UID：${gateway.uidOrNull() ?: "未知"}")
            appendLine("UserService：${if (gateway.isConnected) "已连接" else "未连接"}")
            appendLine("悬浮窗：${if (overlayGranted) "已授权" else "未授权"}")
            appendLine("通知：${if (notificationGranted) "已授权" else "未授权"}")
            appendLine("已启用输入法：${enabledImes.size}")
            append("设备：${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}")
        }
    }

    private fun setStepStatus(view: TextView, text: String, ready: Boolean, optional: Boolean = false) {
        view.text = text
        view.setTextColor(
            themeColor(
                when {
                    ready -> com.google.android.material.R.attr.colorPrimary
                    optional -> com.google.android.material.R.attr.colorTertiary
                    else -> com.google.android.material.R.attr.colorOnSurfaceVariant
                }
            )
        )
    }

    private fun focusImeSelection() {
        scrollView.post {
            scrollView.smoothScrollTo(0, inputMethodSection.top)
            val target = if (selectedImeId(primaryImeIndex) == selectedImeId(secondaryImeIndex)) {
                secondaryImeField
            } else {
                primaryImeField
            }
            target.requestFocus()
            target.showDropDown()
            target.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
        }
    }

    private fun openShizuku() {
        val intent = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (intent == null) toast("未找到 Shizuku，请先安装并启动") else startActivity(intent)
    }

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
    }

    private fun setAdvancedExpanded(expanded: Boolean) {
        advancedContent.visibility = if (expanded) View.VISIBLE else View.GONE
        advancedToggle.text = if (expanded) "高级设置与诊断  收起" else "高级设置与诊断  展开"
        advancedToggle.stateDescription = if (expanded) "已展开" else "已折叠"
        if (expanded) refreshStatus()
    }

    private fun buildButtonSizeSection(index: Int): LinearLayout {
        val section = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val label = TextView(this).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
            setPadding(0, dp(16), 0, 0)
        }
        buttonSizeLabels += label
        section.addView(label, matchParent())
        val seekBar = SeekBar(this).apply {
            max = BUTTON_SIZE_MAX_DP - BUTTON_SIZE_MIN_DP
            contentDescription = "调整悬浮按钮${index + 1}大小"
            minHeight = dp(48)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    updateButtonSizeLabel(index, progress + BUTTON_SIZE_MIN_DP)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val size = (seekBar?.progress ?: 0) + BUTTON_SIZE_MIN_DP
                    lifecycleScope.launch { settingsRepository.setButtonSizeDp(index, size) }
                }
            })
        }
        buttonSizeSeekBars += seekBar
        val preview = FloatingImeButtonView(this).apply {
            setState("切换", false, buttonNumber = index + 1)
            isClickable = false
            isFocusable = false
            contentDescription = "悬浮按钮${index + 1}大小预览"
        }
        buttonSizePreviews += preview
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            seekBar,
            LinearLayout.LayoutParams(0, dp(72), 1f).apply { marginEnd = dp(12) }
        )
        row.addView(FrameLayout(this).apply {
            addView(
                preview,
                FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER)
            )
        }, LinearLayout.LayoutParams(dp(72), dp(72)))
        section.addView(row, matchParent())
        buttonSizeSections += section
        return section
    }

    private fun syncOverlayButtonControls(settings: AppSettings) {
        if (!::buttonCountGroup.isInitialized) return
        val checkedId = buttonCountIds[settings.overlayButtonCount - 1]
        if (buttonCountGroup.checkedButtonId != checkedId) buttonCountGroup.check(checkedId)
        repeat(MAX_OVERLAY_BUTTONS) { index ->
            val size = settings.overlayButton(index).sizeDp
            buttonSizeSections[index].visibility =
                if (index < settings.overlayButtonCount) View.VISIBLE else View.GONE
            val progress = size - BUTTON_SIZE_MIN_DP
            if (buttonSizeSeekBars[index].progress != progress) {
                buttonSizeSeekBars[index].progress = progress
            }
            updateButtonSizeLabel(index, size)
        }
    }

    private fun updateButtonSizeLabel(index: Int, sizeDp: Int) {
        if (buttonSizeLabels.size <= index) return
        buttonSizeLabels[index].text = getString(R.string.overlay_button_size, index + 1, sizeDp)
        buttonSizeSeekBars[index].stateDescription = "$sizeDp dp"
        buttonSizePreviews[index].apply {
            setVisualDiameter(dp(sizeDp))
            layoutParams = layoutParams.apply {
                width = dp(sizeDp)
                height = dp(sizeDp)
            }
            stateDescription = "$sizeDp dp"
        }
    }

    private fun selectedImeId(index: Int): String? = enabledImes.getOrNull(index)?.id

    private fun imeDropdown(hint: String): Pair<TextInputLayout, MaterialAutoCompleteTextView> {
        val field = MaterialAutoCompleteTextView(this).apply {
            inputType = InputType.TYPE_NULL
            isSingleLine = true
            contentDescription = hint
            setOnClickListener { showDropDown() }
            setOnFocusChangeListener { _, hasFocus -> if (hasFocus) showDropDown() }
        }
        val layout = TextInputLayout(this).apply {
            this.hint = hint
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
            addView(field, matchParent())
        }
        return layout to field
    }

    private fun addCheckRow(parent: LinearLayout, label: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
        }
        row.addView(TextView(this).apply {
            text = label
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
        }, weighted())
        val status = TextView(this).apply {
            text = "检查中"
            gravity = Gravity.END
            isSingleLine = true
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
        }
        row.addView(status, wrapContent())
        parent.addView(row, matchParent())
        return status
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
        setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
        setPadding(0, dp(24), 0, dp(8))
    }

    private fun bodyText(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        setLineSpacing(0f, 1.08f)
    }

    private fun smallLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
        setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
    }

    private fun diagnosticPanel(initialText: String): TextView = TextView(this).apply {
        text = initialText
        setTextIsSelectable(true)
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        setBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurfaceContainer))
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }

    private fun outlinedButton(text: String, action: () -> Unit): MaterialButton = MaterialButton(
        this,
        null,
        com.google.android.material.R.attr.materialButtonOutlinedStyle
    ).apply {
        this.text = text
        isAllCaps = false
        minHeight = dp(48)
        setOnClickListener { action() }
    }

    private fun themeColor(attribute: Int): Int = MaterialColors.getColor(this, attribute, Color.GRAY)

    private fun matchParent(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun wrapContent(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun weighted(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        0,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        1f
    )

    private fun withTopMargin(topDp: Int): LinearLayout.LayoutParams = matchParent().apply {
        topMargin = dp(topDp)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        private const val SHIZUKU_REQUEST_CODE = 1001
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val BUTTON_SIZE_MIN_DP = 36
        private const val BUTTON_SIZE_MAX_DP = 64
    }
}
