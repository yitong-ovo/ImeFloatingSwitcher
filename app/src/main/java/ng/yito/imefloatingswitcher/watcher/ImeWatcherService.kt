package ng.yito.imefloatingswitcher.watcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import ng.yito.imefloatingswitcher.MainActivity
import ng.yito.imefloatingswitcher.ime.EnabledImeRepository
import ng.yito.imefloatingswitcher.ime.ImeIdValidator
import ng.yito.imefloatingswitcher.ime.ImeSwitchCoordinator
import ng.yito.imefloatingswitcher.ime.currentImeId
import ng.yito.imefloatingswitcher.ime.currentImeSubtypeHash
import ng.yito.imefloatingswitcher.model.ImeStatus
import ng.yito.imefloatingswitcher.overlay.OverlayController
import ng.yito.imefloatingswitcher.settings.AppSettings
import ng.yito.imefloatingswitcher.settings.AppSettingsRepository
import ng.yito.imefloatingswitcher.shizuku.ShizukuGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class ImeWatcherService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var settingsRepository: AppSettingsRepository
    private lateinit var enabledImeRepository: EnabledImeRepository
    private lateinit var overlayController: OverlayController
    private lateinit var gateway: ShizukuGateway
    private lateinit var pollingPolicy: AdaptivePollingPolicy
    private lateinit var screenReceiver: BroadcastReceiver
    private lateinit var imeObserver: android.database.ContentObserver
    private var watchJob: Job? = null
    @Volatile private var appSettings = AppSettings()
    @Volatile private var screenActive = true
    @Volatile private var lastPublicImeId: String? = null
    @Volatile private var currentState = WatcherServiceState.STOPPED
    @Volatile private var overlayAttached = false
    @Volatile private var switchingUntil = 0L
    private val switching = AtomicBoolean(false)
    private var reducer = WatcherStateReducer()

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        settingsRepository = AppSettingsRepository(this)
        enabledImeRepository = EnabledImeRepository(this)
        pollingPolicy = AdaptivePollingPolicy()
        screenActive = isScreenAndUnlocked()
        overlayController = OverlayController(
            context = this,
            savePosition = { index, x, y ->
                serviceScope.launch { settingsRepository.savePosition(index, x, y) }
            },
            onClick = ::handleOverlayClick,
            onVisibilityChanged = { visible ->
                overlayAttached = visible
                if (!visible && currentState == WatcherServiceState.WATCHING_VISIBLE) {
                    mainHandler.post { updateNotification("键盘显示，正在重建悬浮按钮") }
                }
            }
        )
        gateway = ShizukuGateway(this) { connected ->
            mainHandler.post {
                if (!connected) overlayController.hide()
                updateNotification(if (connected) "Shizuku UserService 已连接" else "等待 Shizuku UserService")
            }
        }
        registerScreenReceiver()
        registerImeObserver()
        serviceScope.launch {
            settingsRepository.flow.collectLatest { appSettings = it }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_PAUSE -> {
                screenActive = false
                overlayController.hide()
                updateNotification("监测已暂停")
                return START_STICKY
            }

            ACTION_RESUME -> {
                screenActive = isScreenAndUnlocked()
            }
        }
        ensureForeground()
        if (watchJob?.isActive != true) watchJob = serviceScope.launch { watchLoop() }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        currentState = WatcherServiceState.STOPPED
        watchJob?.cancel()
        serviceScope.cancel()
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { contentResolver.unregisterContentObserver(imeObserver) }
        overlayController.hide()
        gateway.unbind()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private suspend fun watchLoop() {
        var lastStatus = -1
        var stateChangedAt = 0L
        var consecutiveErrors = 0
        while (serviceScope.isActive) {
            if (!screenActive) {
                currentState = WatcherServiceState.WATCHING_HIDDEN
                overlayController.hide()
                delay(1_000L)
                continue
            }
            val settings = appSettings
            val primary = settings.primaryImeId
            val secondary = settings.secondaryImeId
            if (!ImeIdValidator.isValid(primary) || !ImeIdValidator.isValid(secondary)) {
                currentState = WatcherServiceState.ERROR
                overlayController.hide()
                updateNotification("请先选择两个已启用输入法")
                delay(1_000L)
                continue
            }
            if (!overlayController.canDrawOverlays()) {
                currentState = WatcherServiceState.WAITING_FOR_PERMISSION
                overlayController.hide()
                updateNotification("需要悬浮窗权限")
                delay(1_000L)
                continue
            }
            if (!gateway.isBinderAvailable()) {
                currentState = WatcherServiceState.WAITING_FOR_SHIZUKU
                overlayController.hide()
                updateNotification("Shizuku 未运行")
                delay(1_500L)
                continue
            }
            if (!gateway.isPermissionGranted()) {
                currentState = WatcherServiceState.WAITING_FOR_PERMISSION
                overlayController.hide()
                updateNotification("等待 Shizuku 授权")
                delay(1_500L)
                continue
            }
            if (!gateway.isConnected) {
                currentState = WatcherServiceState.WAITING_FOR_SHIZUKU
                gateway.bind()
                delay(500L)
                continue
            }

            val snapshot = gateway.readImeSnapshot()
            val now = android.os.SystemClock.elapsedRealtime()
            val status = snapshot.status
            if (status != lastStatus) {
                stateChangedAt = now
                lastStatus = status
            }
            if (status == ImeStatus.ERROR) consecutiveErrors++ else consecutiveErrors = 0
            lastPublicImeId = currentImeId() ?: lastPublicImeId
            val decision = reducer.reduce(
                status = status,
                now = now,
                switchingUntil = switchingUntil,
                manualAlwaysVisible = settings.manualAlwaysVisible
            )
            currentState = decision.state
            val show = decision.overlayVisible &&
                (settings.manualAlwaysVisible || status != ImeStatus.ERROR || now - snapshot.timestampElapsedRealtime <= 2_000L)
            if (show) {
                overlayController.update(
                    settings = settings,
                    currentImeId = lastPublicImeId,
                    primaryId = primary,
                    secondaryId = secondary,
                    switching = switching.get() || switchingUntil > now
                )
            } else {
                overlayController.hide()
            }
            val notificationState = if (decision.overlayVisible &&
                !(overlayAttached && overlayController.isShown())
            ) {
                "键盘显示，正在重建悬浮按钮"
            } else {
                stateLabel(currentState)
            }
            updateNotification(notificationState)
            delay(
                pollingPolicy.nextDelay(
                    status = status,
                    now = now,
                    stateChangedAt = stateChangedAt,
                    switchingUntil = switchingUntil,
                    consecutiveErrors = consecutiveErrors
                )
            )
        }
    }

    private fun handleOverlayClick() {
        if (!screenActive || switching.get()) return
        val settings = appSettings
        val primary = settings.primaryImeId ?: return
        val secondary = settings.secondaryImeId ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!switching.compareAndSet(false, true)) return
        switchingUntil = max(switchingUntil, now + 1_500L)
        val currentForLabel = lastPublicImeId
        overlayController.update(settings, currentForLabel, primary, secondary, switching = true)
        serviceScope.launch {
            val (current, currentSubtypeHash) = withContext(Dispatchers.IO) {
                val current = currentImeId() ?: lastPublicImeId
                current to currentImeSubtypeHash()
            }
            val target = ImeSwitchCoordinator.chooseTarget(current, primary, secondary)
            if (currentSubtypeHash != null && (current == primary || current == secondary)) {
                settingsRepository.rememberImeSubtype(current, currentSubtypeHash)
            }
            val enabled = withContext(Dispatchers.IO) { enabledImeRepository.isEnabled(target) }
            if (!enabled) {
                switching.set(false)
                updateNotification("目标输入法已禁用，请重新选择")
                return@launch
            }
            val rememberedSubtypeHash = settingsRepository.rememberedImeSubtypeHash(target)
            val result = gateway.setInputMethod(target, rememberedSubtypeHash)
            switching.set(false)
            if (result.success) {
                updateNotification("已切换输入法（${result.elapsedMs}ms）")
            } else {
                updateNotification("输入法切换失败：${result.errorMessage ?: "未知错误"}")
            }
        }
    }

    private fun registerScreenReceiver() {
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        screenActive = false
                        overlayController.hide()
                    }
                    Intent.ACTION_SCREEN_ON -> screenActive = isScreenAndUnlocked()
                    Intent.ACTION_USER_PRESENT -> screenActive = true
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    private fun registerImeObserver() {
        lastPublicImeId = currentImeId()
        imeObserver = object : android.database.ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                lastPublicImeId = currentImeId() ?: lastPublicImeId
            }
        }
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD),
            false,
            imeObserver
        )
    }

    private fun isScreenAndUnlocked(): Boolean {
        val power = getSystemService(PowerManager::class.java)
        val keyguard = getSystemService(KeyguardManager::class.java)
        return power?.isInteractive == true && keyguard?.isKeyguardLocked != true
    }

    private fun ensureForeground() {
        createNotificationChannel()
        val notification = buildNotification("启动监测中")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        if (!::notificationManager.isInitialized) return
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private lateinit var notificationManager: NotificationManager

    private fun createNotificationChannel() {
        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "输入法切换监测", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseIntent = PendingIntent.getService(
            this,
            101,
            Intent(this, ImeWatcherService::class.java).setAction(
                if (screenActive) ACTION_PAUSE else ACTION_RESUME
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            102,
            Intent(this, ImeWatcherService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("输入法悬浮切换")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, if (screenActive) "暂停" else "继续", pauseIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopIntent)
            .build()
    }

    private fun stateLabel(state: WatcherServiceState): String = when (state) {
        WatcherServiceState.WATCHING_VISIBLE -> "键盘显示，悬浮按钮已显示"
        WatcherServiceState.WATCHING_HIDDEN -> "键盘隐藏"
        WatcherServiceState.SWITCHING -> "正在切换输入法"
        WatcherServiceState.DEGRADED_MANUAL_MODE -> "手动常显模式"
        WatcherServiceState.WAITING_FOR_SHIZUKU -> "等待 Shizuku"
        WatcherServiceState.WAITING_FOR_PERMISSION -> "等待授权"
        WatcherServiceState.ERROR -> "状态读取异常"
        WatcherServiceState.STOPPED -> "已停止"
    }

    companion object {
        @Volatile
        var isRunning = false
            private set

        const val ACTION_START = "ng.yito.imefloatingswitcher.action.START"
        const val ACTION_STOP = "ng.yito.imefloatingswitcher.action.STOP"
        const val ACTION_PAUSE = "ng.yito.imefloatingswitcher.action.PAUSE"
        const val ACTION_RESUME = "ng.yito.imefloatingswitcher.action.RESUME"
        private const val CHANNEL_ID = "ime_watcher"
        private const val NOTIFICATION_ID = 4001
    }
}
