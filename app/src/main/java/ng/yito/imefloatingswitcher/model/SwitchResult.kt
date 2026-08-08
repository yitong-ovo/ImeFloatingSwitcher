package ng.yito.imefloatingswitcher.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class SwitchResult(
    val success: Boolean,
    val exitCode: Int,
    val stderr: String,
    val errorCode: String?,
    val errorMessage: String?,
    val elapsedMs: Long
) : Parcelable {
    companion object {
        fun failure(code: String, message: String, elapsedMs: Long = 0L): SwitchResult =
            SwitchResult(
                success = false,
                exitCode = -1,
                stderr = "",
                errorCode = code,
                errorMessage = message.take(240),
                elapsedMs = elapsedMs
            )
    }
}
