package ng.yito.imefloatingswitcher.shizuku

import android.content.Context
import android.os.IBinder
import android.os.SystemClock
import androidx.annotation.Keep
import android.view.inputmethod.InputMethodManager
import ng.yito.imefloatingswitcher.ime.ImeIdValidator
import ng.yito.imefloatingswitcher.model.ImeSnapshot
import ng.yito.imefloatingswitcher.model.SwitchResult
import ng.yito.imefloatingswitcher.parser.TextImeDumpParser
import ng.yito.imefloatingswitcher.shizuku.IPrivilegedImeService.Stub
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Shizuku UserService. It is deliberately an AIDL Stub, not an Android
 * Service: Shizuku starts it in a shell/root identity process.
 */
class PrivilegedImeUserService() : Stub() {
    private var userServiceContext: Context? = null
    private val dumpReader = BinderDumpReader()
    private val commandExecutor = Executors.newCachedThreadPool()

    @Keep
    constructor(context: Context) : this() {
        userServiceContext = context.applicationContext
    }

    override fun readImeSnapshot(): ImeSnapshot {
        val started = SystemClock.elapsedRealtime()
        return try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
            val binder = getService.invoke(null, "input_method") as? IBinder
                ?: return ImeSnapshot.error("INPUT_METHOD_BINDER_MISSING", "input_method binder is unavailable")
            if (!binder.isBinderAlive) {
                return ImeSnapshot.error("INPUT_METHOD_BINDER_DEAD", "input_method binder is not alive")
            }
            val result = dumpReader.readCritical(binder)
            if (!result.success) {
                ImeSnapshot.error(
                    result.errorCode ?: "BINDER_DUMP_FAILED",
                    result.errorMessage ?: "input_method dump failed",
                    SystemClock.elapsedRealtime() - started
                )
            } else {
                val parsed = TextImeDumpParser().parse(
                    result.text,
                    elapsedMs = SystemClock.elapsedRealtime() - started
                )
                parsed.copy(
                    rawSizeBytes = result.rawSizeBytes,
                    dumpTruncated = result.truncated,
                    errorCode = parsed.errorCode ?: if (result.truncated) "DUMP_TRUNCATED" else null,
                    errorMessage = parsed.errorMessage ?: if (result.truncated) {
                        "Binder dump exceeded the capture limit; keyword lines were retained for parsing."
                    } else null
                )
            }
        } catch (error: Throwable) {
            ImeSnapshot.error(
                "USER_SERVICE_READ_FAILED",
                error.message ?: error.javaClass.simpleName,
                SystemClock.elapsedRealtime() - started
            )
        }
    }

    override fun setInputMethod(imeId: String): SwitchResult {
        if (!ImeIdValidator.isValid(imeId)) {
            return SwitchResult.failure("INVALID_IME_ID", "The requested input method ID is not valid.")
        }
        val enabled = runCatching {
            userServiceContext
                ?.getSystemService(InputMethodManager::class.java)
                ?.enabledInputMethodList
                ?.any { it.id == imeId } == true
        }.getOrDefault(false)
        if (!enabled) {
            return SwitchResult.failure("IME_NOT_ENABLED", "The requested input method is not enabled.")
        }
        val target = imeId
        val primary = listOf("/system/bin/ime", "set", "--user", "current", target)
        val first = runCommand(primary)
        if (first.success) return first

        val fallback = listOf("/system/bin/cmd", "input_method", "ime", "set", "--user", "current", target)
        val second = runCommand(fallback)
        return if (second.success) {
            second.copy(stderr = "primary failed: ${first.stderr.take(160)}")
        } else {
            second.copy(
                errorCode = second.errorCode ?: "IME_SWITCH_FAILED",
                errorMessage = "ime failed (${first.exitCode}); cmd input_method failed (${second.exitCode})"
            )
        }
    }

    override fun destroy() {
        dumpReader.close()
        commandExecutor.shutdownNow()
        System.exit(0)
    }

    private fun runCommand(command: List<String>): SwitchResult {
        val started = SystemClock.elapsedRealtime()
        val process = try {
            ProcessBuilder(command).redirectErrorStream(false).start()
        } catch (error: Throwable) {
            return SwitchResult(
                success = false,
                exitCode = -1,
                stderr = "",
                errorCode = "PROCESS_START_FAILED",
                errorMessage = error.message,
                elapsedMs = SystemClock.elapsedRealtime() - started
            )
        }

        val stdoutFuture = commandExecutor.submit<String> { readStream(process.inputStream) }
        val stderrFuture = commandExecutor.submit<String> { readStream(process.errorStream) }
        val finished = try {
            process.waitFor(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) process.destroyForcibly()

        getOutput(stdoutFuture)
        val stderr = getOutput(stderrFuture)
        val exitCode = if (finished) process.exitValue() else -2
        return SwitchResult(
            success = finished && exitCode == 0,
            exitCode = exitCode,
            stderr = stderr,
            errorCode = if (finished && exitCode == 0) null else "COMMAND_FAILED",
            errorMessage = if (finished && exitCode == 0) null else stderr.ifBlank { "command did not complete successfully" },
            elapsedMs = SystemClock.elapsedRealtime() - started
        )
    }

    private fun getOutput(future: java.util.concurrent.Future<String>): String = try {
        future.get(400, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        future.cancel(true)
        ""
    } catch (_: Throwable) {
        ""
    }

    private fun readStream(input: InputStream): String = runCatching {
        val output = ByteArrayOutputStream(32 * 1024)
        val buffer = ByteArray(8 * 1024)
        var remaining = 32 * 1024
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) break
            output.write(buffer, 0, count)
            remaining -= count
        }
        String(output.toByteArray(), Charsets.UTF_8).take(32 * 1024)
    }.getOrDefault("")
}
