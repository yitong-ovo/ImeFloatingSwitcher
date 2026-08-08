package ng.yito.imefloatingswitcher.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import ng.yito.imefloatingswitcher.model.ImeSnapshot
import ng.yito.imefloatingswitcher.model.SwitchResult
import ng.yito.imefloatingswitcher.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import android.os.SystemClock

class ShizukuGateway(
    context: Context,
    private val onConnectionChanged: (Boolean) -> Unit = {}
) {
    private val appContext = context.applicationContext
    @Volatile private var service: IPrivilegedImeService? = null
    private var connection: PrivilegedServiceConnection? = null
    private var args: Shizuku.UserServiceArgs? = null
    private val binding = AtomicBoolean(false)
    private val bindStartedAt = AtomicLong(0L)
    private val connectionGeneration = AtomicLong(0L)

    val isConnected: Boolean
        get() = service?.asBinder()?.isBinderAlive == true

    fun isBinderAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun isPermissionGranted(): Boolean = runCatching {
        isBinderAvailable() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun uidOrNull(): Int? = runCatching { if (isBinderAvailable()) Shizuku.getUid() else null }.getOrNull()

    fun requestPermission(requestCode: Int) {
        runCatching { Shizuku.requestPermission(requestCode) }
    }

    @Synchronized
    fun bind() {
        if (!isBinderAvailable() || !isPermissionGranted()) return
        if (isConnected) return
        if (service != null) clearConnection(notify = false)
        if (binding.get()) {
            val elapsed = SystemClock.elapsedRealtime() - bindStartedAt.get()
            if (elapsed < BIND_TIMEOUT_MS) return
            clearConnection(notify = false)
        }

        val generation = connectionGeneration.incrementAndGet()
        val localArgs = Shizuku.UserServiceArgs(
            ComponentName(appContext.packageName, PrivilegedImeUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("ime-service")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
        val localConnection = PrivilegedServiceConnection(
            onConnected = { connectedService ->
                if (connectionGeneration.get() == generation) {
                    service = connectedService
                    binding.set(false)
                    bindStartedAt.set(0L)
                    onConnectionChanged(true)
                }
            },
            onDisconnected = {
                if (connectionGeneration.get() == generation) {
                    service = null
                    binding.set(false)
                    bindStartedAt.set(0L)
                    onConnectionChanged(false)
                }
            }
        )
        args = localArgs
        connection = localConnection
        binding.set(true)
        bindStartedAt.set(SystemClock.elapsedRealtime())
        runCatching { Shizuku.bindUserService(localArgs, localConnection) }
            .onFailure {
                if (connectionGeneration.get() != generation) return@onFailure
                binding.set(false)
                bindStartedAt.set(0L)
                service = null
                onConnectionChanged(false)
            }
    }

    @Synchronized
    fun unbind() {
        connectionGeneration.incrementAndGet()
        val localArgs = args
        val localConnection = connection
        if (localArgs != null && localConnection != null) {
            runCatching { Shizuku.unbindUserService(localArgs, localConnection, true) }
        }
        clearConnection(notify = true)
    }

    suspend fun readImeSnapshot(): ImeSnapshot = withContext(Dispatchers.IO) {
        val remote = service ?: return@withContext ImeSnapshot.error("USER_SERVICE_UNAVAILABLE", "Shizuku UserService is not connected")
        runCatching { remote.readImeSnapshot() }.getOrElse {
            disconnectAndAllowRetry()
            ImeSnapshot.error("USER_SERVICE_CALL_FAILED", it.message ?: it.javaClass.simpleName)
        }
    }

    suspend fun setInputMethod(imeId: String, subtypeHash: Int?): SwitchResult = withContext(Dispatchers.IO) {
        val remote = service ?: return@withContext SwitchResult.failure("USER_SERVICE_UNAVAILABLE", "Shizuku UserService is not connected")
        runCatching { remote.setInputMethod(imeId, subtypeHash ?: 0, subtypeHash != null) }.getOrElse {
            disconnectAndAllowRetry()
            SwitchResult.failure("USER_SERVICE_CALL_FAILED", it.message ?: it.javaClass.simpleName)
        }
    }

    @Synchronized
    private fun disconnectAndAllowRetry() {
        clearConnection(notify = true)
    }

    private fun clearConnection(notify: Boolean) {
        val wasConnected = service != null || binding.get()
        service = null
        binding.set(false)
        bindStartedAt.set(0L)
        if (notify && wasConnected) onConnectionChanged(false)
    }

    private companion object {
        const val BIND_TIMEOUT_MS = 3_000L
    }
}
