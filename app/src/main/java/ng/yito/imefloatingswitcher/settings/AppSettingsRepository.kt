package ng.yito.imefloatingswitcher.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.imeSwitcherDataStore by preferencesDataStore(name = "ime_switcher_settings")
private fun imeSubtypeKey(imeId: String) = intPreferencesKey("ime_subtype:$imeId")

data class AppSettings(
    val primaryImeId: String? = null,
    val secondaryImeId: String? = null,
    val primaryImeSubtypeHash: Int? = null,
    val secondaryImeSubtypeHash: Int? = null,
    val manualAlwaysVisible: Boolean = false,
    val buttonSizeDp: Int = 48,
    val positionX: Int? = null,
    val positionY: Int? = null
) {
    fun rememberedSubtypeHash(imeId: String): Int? = when (imeId) {
        primaryImeId -> primaryImeSubtypeHash
        secondaryImeId -> secondaryImeSubtypeHash
        else -> null
    }
}

class AppSettingsRepository(private val context: Context) {
    private object Keys {
        val primaryImeId = stringPreferencesKey("primary_ime_id")
        val secondaryImeId = stringPreferencesKey("secondary_ime_id")
        val manualAlwaysVisible = booleanPreferencesKey("manual_always_visible")
        val buttonSizeDp = intPreferencesKey("button_size_dp")
        val positionX = intPreferencesKey("position_x")
        val positionY = intPreferencesKey("position_y")
    }

    val flow: Flow<AppSettings> = context.imeSwitcherDataStore.data.map { preferences ->
        val primaryImeId = preferences[Keys.primaryImeId]
        val secondaryImeId = preferences[Keys.secondaryImeId]
        AppSettings(
            primaryImeId = primaryImeId,
            secondaryImeId = secondaryImeId,
            primaryImeSubtypeHash = primaryImeId?.let { preferences[imeSubtypeKey(it)] },
            secondaryImeSubtypeHash = secondaryImeId?.let { preferences[imeSubtypeKey(it)] },
            manualAlwaysVisible = preferences[Keys.manualAlwaysVisible] ?: false,
            buttonSizeDp = (preferences[Keys.buttonSizeDp] ?: 48).coerceIn(36, 64),
            positionX = preferences[Keys.positionX],
            positionY = preferences[Keys.positionY]
        )
    }

    suspend fun saveImeSelection(primary: String?, secondary: String?) {
        context.imeSwitcherDataStore.edit { preferences ->
            if (primary == null) preferences.remove(Keys.primaryImeId) else preferences[Keys.primaryImeId] = primary
            if (secondary == null) preferences.remove(Keys.secondaryImeId) else preferences[Keys.secondaryImeId] = secondary
        }
    }

    suspend fun rememberImeSubtype(imeId: String, subtypeHash: Int) {
        context.imeSwitcherDataStore.edit { it[imeSubtypeKey(imeId)] = subtypeHash }
    }

    suspend fun setManualAlwaysVisible(enabled: Boolean) {
        context.imeSwitcherDataStore.edit { it[Keys.manualAlwaysVisible] = enabled }
    }

    suspend fun savePosition(x: Int, y: Int) {
        context.imeSwitcherDataStore.edit {
            it[Keys.positionX] = x
            it[Keys.positionY] = y
        }
    }

    suspend fun setButtonSizeDp(size: Int) {
        context.imeSwitcherDataStore.edit { it[Keys.buttonSizeDp] = size.coerceIn(36, 64) }
    }
}
