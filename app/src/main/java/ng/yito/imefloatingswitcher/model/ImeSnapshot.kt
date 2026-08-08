package ng.yito.imefloatingswitcher.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

object ImeStatus {
    const val SHOWN = 1
    const val HIDDEN = 2
    const val UNKNOWN = 3
    const val ERROR = 4

    fun label(status: Int): String = when (status) {
        SHOWN -> "SHOWN"
        HIDDEN -> "HIDDEN"
        UNKNOWN -> "UNKNOWN"
        ERROR -> "ERROR"
        else -> "UNSET"
    }
}

@Parcelize
data class ImeSnapshot(
    val status: Int,
    val inputShown: Boolean?,
    val imeWindowVisibility: Int?,
    val currentImeIdFromDump: String?,
    val keywordLines: String?,
    val showRequested: Boolean?,
    val elapsedMs: Long,
    val rawSizeBytes: Int,
    val dumpTruncated: Boolean,
    val errorCode: String?,
    val errorMessage: String?,
    val timestampElapsedRealtime: Long
) : Parcelable {
    companion object {
        fun error(code: String, message: String, elapsedMs: Long = 0L): ImeSnapshot =
            ImeSnapshot(
                status = ImeStatus.ERROR,
                inputShown = null,
                imeWindowVisibility = null,
                currentImeIdFromDump = null,
                keywordLines = null,
                showRequested = null,
                elapsedMs = elapsedMs,
                rawSizeBytes = 0,
                dumpTruncated = false,
                errorCode = code,
                errorMessage = message.take(240),
                timestampElapsedRealtime = android.os.SystemClock.elapsedRealtime()
            )
    }
}
