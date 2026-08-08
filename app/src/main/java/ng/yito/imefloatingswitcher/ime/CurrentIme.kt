package ng.yito.imefloatingswitcher.ime

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

fun Context.currentImeId(): String? = runCatching {
    if (Build.VERSION.SDK_INT >= 34) {
        getSystemService(InputMethodManager::class.java).currentInputMethodInfo?.id
    } else {
        Settings.Secure.getString(
            contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        )
    }
}.getOrNull()

@Suppress("DEPRECATION")
fun Context.currentImeSubtypeHash(): Int? = runCatching {
    getSystemService(InputMethodManager::class.java)
        .currentInputMethodSubtype
        ?.hashCode()
}.getOrNull()
