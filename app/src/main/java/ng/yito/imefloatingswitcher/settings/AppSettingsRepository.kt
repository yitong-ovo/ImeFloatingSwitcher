package ng.yito.imefloatingswitcher.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.imeSwitcherDataStore by preferencesDataStore(name = "ime_switcher_settings")
private fun imeSubtypeKey(imeId: String) = intPreferencesKey("ime_subtype:$imeId")

const val MAX_OVERLAY_BUTTONS = 3

data class OverlayButtonSettings(
    val sizeDp: Int = 48,
    val positionX: Int? = null,
    val positionY: Int? = null
)

data class AppSettings(
    val primaryImeId: String? = null,
    val secondaryImeId: String? = null,
    val primaryImeSubtypeHash: Int? = null,
    val secondaryImeSubtypeHash: Int? = null,
    val manualAlwaysVisible: Boolean = false,
    val overlayButtonCount: Int = 1,
    val overlayButtons: List<OverlayButtonSettings> = List(MAX_OVERLAY_BUTTONS) {
        OverlayButtonSettings()
    }
) {
    fun rememberedSubtypeHash(imeId: String): Int? = when (imeId) {
        primaryImeId -> primaryImeSubtypeHash
        secondaryImeId -> secondaryImeSubtypeHash
        else -> null
    }

    fun overlayButton(index: Int): OverlayButtonSettings =
        overlayButtons.getOrElse(index) { OverlayButtonSettings() }
}

class AppSettingsRepository(private val context: Context) {
    private object Keys {
        val primaryImeId = stringPreferencesKey("primary_ime_id")
        val secondaryImeId = stringPreferencesKey("secondary_ime_id")
        val manualAlwaysVisible = booleanPreferencesKey("manual_always_visible")
        val overlayButtonCount = intPreferencesKey("button_count")
        val buttonSizeDp = listOf(
            intPreferencesKey("button_size_dp"),
            intPreferencesKey("button_size_dp_2"),
            intPreferencesKey("button_size_dp_3")
        )
        val positionX = listOf(
            intPreferencesKey("position_x"),
            intPreferencesKey("position_x_2"),
            intPreferencesKey("position_x_3")
        )
        val positionY = listOf(
            intPreferencesKey("position_y"),
            intPreferencesKey("position_y_2"),
            intPreferencesKey("position_y_3")
        )
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
            overlayButtonCount = (preferences[Keys.overlayButtonCount] ?: 1)
                .coerceIn(1, MAX_OVERLAY_BUTTONS),
            overlayButtons = List(MAX_OVERLAY_BUTTONS) { index ->
                OverlayButtonSettings(
                    sizeDp = (preferences[Keys.buttonSizeDp[index]] ?: 48).coerceIn(36, 64),
                    positionX = preferences[Keys.positionX[index]],
                    positionY = preferences[Keys.positionY[index]]
                )
            }
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

    suspend fun rememberedImeSubtypeHash(imeId: String): Int? =
        context.imeSwitcherDataStore.data.first()[imeSubtypeKey(imeId)]

    suspend fun setManualAlwaysVisible(enabled: Boolean) {
        context.imeSwitcherDataStore.edit { it[Keys.manualAlwaysVisible] = enabled }
    }

    suspend fun setOverlayButtonCount(count: Int) {
        context.imeSwitcherDataStore.edit {
            it[Keys.overlayButtonCount] = count.coerceIn(1, MAX_OVERLAY_BUTTONS)
        }
    }

    suspend fun savePosition(index: Int, x: Int, y: Int) {
        require(index in 0 until MAX_OVERLAY_BUTTONS)
        context.imeSwitcherDataStore.edit {
            it[Keys.positionX[index]] = x
            it[Keys.positionY[index]] = y
        }
    }

    suspend fun setButtonSizeDp(index: Int, size: Int) {
        require(index in 0 until MAX_OVERLAY_BUTTONS)
        context.imeSwitcherDataStore.edit {
            it[Keys.buttonSizeDp[index]] = size.coerceIn(36, 64)
        }
    }
}
