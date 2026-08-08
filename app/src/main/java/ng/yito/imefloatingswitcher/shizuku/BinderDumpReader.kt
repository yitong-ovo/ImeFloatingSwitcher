package ng.yito.imefloatingswitcher.shizuku

import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.LinkedHashSet

data class BinderDumpResult(
    val success: Boolean,
    val text: String,
    val rawSizeBytes: Int,
    val truncated: Boolean = false,
    val errorCode: String? = null,
    val errorMessage: String? = null
)

private data class LimitedReadResult(
    val captured: ByteArray,
    val keywordLines: String,
    val totalBytes: Long,
    val truncated: Boolean
)

/** Reads a binder dump without starting a shell process. */
class BinderDumpReader(
    private val timeoutMs: Long = 500L,
    private val maxBytes: Int = 256 * 1024
) {
    private val executor: ExecutorService = Executors.newFixedThreadPool(2)

    fun readCritical(binder: IBinder): BinderDumpResult {
        val pipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (error: Throwable) {
            return BinderDumpResult(
                success = false,
                text = "",
                rawSizeBytes = 0,
                errorCode = "PIPE_CREATE_FAILED",
                errorMessage = error.message
            )
        }

        val readFd = pipe[0]
        val writeFd = pipe[1]
        val readFuture = executor.submit<LimitedReadResult> {
            ParcelFileDescriptor.AutoCloseInputStream(readFd).use { input ->
                readLimited(input)
            }
        }
        val dumpFuture = executor.submit<Throwable?> {
            try {
                binder.dump(writeFd.fileDescriptor, arrayOf("--dump-priority", "CRITICAL"))
                null
            } catch (error: Throwable) {
                error
            } finally {
                runCatching { writeFd.close() }
            }
        }

        return try {
            val dumpError = dumpFuture.get(timeoutMs, TimeUnit.MILLISECONDS)
            if (dumpError != null) {
                BinderDumpResult(
                    success = false,
                    text = "",
                    rawSizeBytes = 0,
                    errorCode = "BINDER_DUMP_FAILED",
                    errorMessage = dumpError.message
                )
            } else {
                val read = readFuture.get(timeoutMs, TimeUnit.MILLISECONDS)
                val capturedText = String(read.captured, Charsets.UTF_8)
                val text = buildString(capturedText.length + read.keywordLines.length + 1) {
                    append(capturedText)
                    if (read.keywordLines.isNotBlank()) {
                        append('\n')
                        append(read.keywordLines)
                    }
                }
                BinderDumpResult(
                    success = true,
                    text = text,
                    rawSizeBytes = read.totalBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    truncated = read.truncated
                )
            }
        } catch (_: TimeoutException) {
            BinderDumpResult(
                success = false,
                text = "",
                rawSizeBytes = 0,
                errorCode = "BINDER_DUMP_TIMEOUT",
                errorMessage = "input_method dump exceeded ${timeoutMs} ms"
            )
        } catch (error: Throwable) {
            BinderDumpResult(
                success = false,
                text = "",
                rawSizeBytes = 0,
                errorCode = "BINDER_DUMP_READ_FAILED",
                errorMessage = error.message
            )
        } finally {
            dumpFuture.cancel(true)
            readFuture.cancel(true)
            runCatching { readFd.close() }
            runCatching { writeFd.close() }
        }
    }

    fun close() {
        executor.shutdownNow()
    }

    /**
     * Captures a bounded prefix, but keeps draining the pipe after the limit.
     * Samsung's IMMS dump can be larger than 256 KiB; treating that as a failed
     * Binder call prevents us from seeing the small visibility fields we need.
     */
    private fun readLimited(input: java.io.InputStream): LimitedReadResult {
        val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
        val keywordLines = LinkedHashSet<String>()
        val pendingLine = StringBuilder()
        val buffer = ByteArray(8192)
        var totalBytes = 0L
        var truncated = false
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            totalBytes += count
            val remaining = maxBytes - output.size()
            if (remaining > 0) {
                output.write(buffer, 0, minOf(remaining, count))
            }
            if (count > remaining) truncated = true

            pendingLine.append(String(buffer, 0, count, Charsets.UTF_8))
            collectCompleteKeywordLines(pendingLine, keywordLines)
        }
        if (pendingLine.isNotEmpty()) collectKeywordLine(pendingLine.toString(), keywordLines)
        return LimitedReadResult(
            captured = output.toByteArray(),
            keywordLines = keywordLines.joinToString("\n"),
            totalBytes = totalBytes,
            truncated = truncated
        )
    }

    private fun collectCompleteKeywordLines(
        pendingLine: StringBuilder,
        keywordLines: MutableSet<String>
    ) {
        while (true) {
            val newline = pendingLine.indexOf("\n")
            if (newline < 0) return
            collectKeywordLine(pendingLine.substring(0, newline), keywordLines)
            pendingLine.delete(0, newline + 1)
        }
    }

    private fun collectKeywordLine(line: String, keywordLines: MutableSet<String>) {
        val normalized = line.trim().trimEnd('\r')
        if (normalized.isBlank() || !keywordPattern.containsMatchIn(normalized)) return
        if (keywordLines.size < MAX_KEYWORD_LINES) keywordLines += normalized.take(MAX_KEYWORD_LINE_LENGTH)
    }

    private companion object {
        const val MAX_KEYWORD_LINES = 128
        const val MAX_KEYWORD_LINE_LENGTH = 4096
        val keywordPattern = Regex("(?i)(InputShown|ImeWindowVis|CurMethod|CurId|ShowRequested)")
    }
}
