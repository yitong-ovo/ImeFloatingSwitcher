package ng.yito.imefloatingswitcher.ime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
class EnabledImeRepository(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val inputMethodManager =
        appContext.getSystemService(InputMethodManager::class.java)

    /**
     * InputMethodManager is the primary source. On some Android 16/One UI
     * builds it can expose only the system/default IME to a regular app even
     * though third-party IMEs are enabled. The secure enabled-IME setting is
     * used as a read-only reconciliation source, and package visibility is
     * declared in the manifest so the missing service can be resolved.
     */
    fun getEnabled(): List<ImeInfo> {
        val byId = linkedMapOf<String, ImeInfo>()
        runCatching { inputMethodManager?.enabledInputMethodList.orEmpty() }
            .getOrDefault(emptyList())
            .map { it.toImeInfo() }
            .forEach { byId[canonicalId(it.id)] = it }

        readSecureEnabledIds().forEach { id ->
            val key = canonicalId(id)
            if (key !in byId) resolveEnabledIme(id)?.let { byId[key] = it }
        }

        return byId.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    fun isEnabled(id: String): Boolean {
        val target = canonicalId(id)
        return getEnabled().any { canonicalId(it.id) == target }
    }

    fun suggestions(items: List<ImeInfo> = getEnabled()): Pair<ImeInfo?, ImeInfo?> {
        val primary = items.firstOrNull { it.packageName == GBOARD_PACKAGE }
        val secondary = items.firstOrNull {
            it.packageName.contains("doubao", ignoreCase = true) ||
                it.id.contains("doubao", ignoreCase = true) ||
                it.label.contains("豆包")
        }
        return primary to secondary
    }

    private fun InputMethodInfo.toImeInfo(): ImeInfo = ImeInfo(
        id = id,
        packageName = packageName,
        label = runCatching { loadLabel(packageManager).toString() }
            .getOrDefault(packageName)
            .ifBlank { packageName }
    )

    private fun readSecureEnabledIds(): List<String> = runCatching {
        Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ENABLED_INPUT_METHODS
        )
            ?.split(':')
            ?.map(String::trim)
            ?.filter(ImeIdValidator::isValid)
            .orEmpty()
    }.getOrDefault(emptyList())

    private fun resolveEnabledIme(id: String): ImeInfo? {
        val component = ComponentName.unflattenFromString(id) ?: return null
        val packageName = component.packageName
        val serviceInfo = runCatching {
            packageManager.queryIntentServices(
                Intent("android.view.InputMethod"),
                PackageManager.GET_META_DATA
            ).asSequence()
                .mapNotNull { it.serviceInfo }
                .firstOrNull { info ->
                    ComponentName(info.packageName, info.name) == component
                }
        }.getOrNull()

        val label = runCatching { serviceInfo?.loadLabel(packageManager)?.toString().orEmpty() }
            .getOrDefault("")
            .ifBlank {
                runCatching {
                    packageManager.getApplicationInfo(packageName, 0)
                        .loadLabel(packageManager)
                        .toString()
                }.getOrDefault(packageName)
            }
        return ImeInfo(id = id, packageName = packageName, label = label.ifBlank { packageName })
    }

    private fun canonicalId(id: String): String =
        ComponentName.unflattenFromString(id)?.flattenToString() ?: id

    companion object {
        const val GBOARD_PACKAGE = "com.google.android.inputmethod.latin"
    }
}
