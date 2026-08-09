package ng.yito.imefloatingswitcher.ime

import android.content.Context
import android.view.inputmethod.InputMethodManager
import java.util.Locale

data class ImeSubtypeLabel(val shortLabel: String, val fullLabel: String)

class InputMethodSubtypeLabelResolver(context: Context) {
    private val appContext = context.applicationContext
    private val inputMethodManager = appContext.getSystemService(InputMethodManager::class.java)

    @Suppress("DEPRECATION")
    fun resolve(imeId: String, subtypeHash: Int?): ImeSubtypeLabel? {
        val info = runCatching {
            inputMethodManager?.enabledInputMethodList?.firstOrNull { it.id == imeId }
        }.getOrNull() ?: return null
        val subtypes = runCatching {
            inputMethodManager?.getEnabledInputMethodSubtypeList(info, true).orEmpty()
        }.getOrDefault(emptyList())
        if (subtypes.size <= 1) return null
        val subtype = subtypeHash?.let { hash -> subtypes.firstOrNull { it.hashCode() == hash } }
            ?: return UNKNOWN_SUBTYPE
        val displayName = runCatching {
            subtype.getDisplayName(appContext, info.packageName, info.serviceInfo.applicationInfo)
                .toString()
        }.getOrDefault("")
        return subtypeLanguageLabel(subtype.languageTag, subtype.locale, displayName)
    }

    companion object {
        val UNKNOWN_SUBTYPE = ImeSubtypeLabel("?", "未知")
    }
}

internal fun subtypeLanguageLabel(
    languageTag: String,
    legacyLocale: String,
    displayName: String
): ImeSubtypeLabel {
    val tag = languageTag.ifBlank { legacyLocale.replace('_', '-') }
    val language = runCatching { Locale.forLanguageTag(tag).language.lowercase(Locale.ROOT) }
        .getOrDefault("")
    return when {
        language == "zh" || displayName.contains("中文") -> ImeSubtypeLabel("中", "中文")
        language == "en" || displayName.contains("English", ignoreCase = true) ||
            displayName.contains("Alphabet", ignoreCase = true) -> ImeSubtypeLabel("EN", "英文")
        else -> {
            val fullLabel = displayName.trim().ifBlank {
                runCatching { Locale.forLanguageTag(tag).getDisplayLanguage(Locale.getDefault()) }
                    .getOrDefault("")
            }.ifBlank { "其他" }
            val shortLabel = if (language.matches(Regex("[a-z]{2,3}"))) {
                language.take(2).uppercase(Locale.ROOT)
            } else {
                fullLabel.take(2)
            }
            ImeSubtypeLabel(shortLabel, fullLabel)
        }
    }
}
