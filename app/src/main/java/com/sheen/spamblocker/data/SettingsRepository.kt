package com.sheen.spamblocker.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sheen.spamblocker.rules.Action
import com.sheen.spamblocker.rules.PrefixDeriver
import com.sheen.spamblocker.screening.ScreeningSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class RefreshInterval(val hours: Long?, val label: String) {
    HOURS_6(6, "Every 6 hours"),
    HOURS_12(12, "Every 12 hours"),
    HOURS_24(24, "Every 24 hours"),
    DAYS_7(24 * 7, "Every 7 days"),
    MANUAL(null, "Manual only"),
}

/** SPEC §9.2, with the defaults from that table. */
data class Settings(
    val blockHidden: Boolean = true,
    val hiddenAction: Action = Action.SILENCE,
    val blockInternational: Boolean = true,
    val internationalAction: Action = Action.SILENCE,
    val trai140Enabled: Boolean = true,
    val trai140Action: Action = Action.REJECT,
    val trai160Allow: Boolean = true,
    val recentOutgoingDays: Int = 7,
    val repeatCallerMinutes: Int = 5,
    val deriveMinCount: Int = PrefixDeriver.DEFAULT_MIN_COUNT,
    val deriveMinPrefixDigits: Int = PrefixDeriver.DEFAULT_MIN_PREFIX_DIGITS,
    val listRefreshInterval: RefreshInterval = RefreshInterval.HOURS_24,
    val listRefreshUnmeteredOnly: Boolean = true,
    val postCallPrompt: Boolean = true,
    val notifyOnBlock: Boolean = true,
    /** Not a user setting: whether the first-run blocked-call import has been offered. */
    val importOffered: Boolean = false,
    /** Not a user setting: the last call-log row considered for a post-call prompt. */
    val lastPromptedCallId: Long = 0,
) {
    fun toScreeningSettings() = ScreeningSettings(
        blockHidden = blockHidden,
        hiddenAction = hiddenAction,
        blockInternational = blockInternational,
        internationalAction = internationalAction,
        trai140Enabled = trai140Enabled,
        trai140Action = trai140Action,
        trai160Allow = trai160Allow,
        recentOutgoingDays = recentOutgoingDays,
        repeatCallerMinutes = repeatCallerMinutes,
    )
}

@Singleton
class SettingsRepository @Inject constructor(private val store: DataStore<Preferences>) {

    val settings: Flow<Settings> = store.data.map(::read)

    suspend fun current(): Settings = settings.first()

    suspend fun update(transform: (Settings) -> Settings) {
        store.edit { prefs -> write(prefs, transform(read(prefs))) }
    }

    private fun read(p: Preferences): Settings {
        val d = Settings()
        return Settings(
            blockHidden = p[BLOCK_HIDDEN] ?: d.blockHidden,
            hiddenAction = p[HIDDEN_ACTION].toEnum(d.hiddenAction),
            blockInternational = p[BLOCK_INTERNATIONAL] ?: d.blockInternational,
            internationalAction = p[INTERNATIONAL_ACTION].toEnum(d.internationalAction),
            trai140Enabled = p[TRAI_140_ENABLED] ?: d.trai140Enabled,
            trai140Action = p[TRAI_140_ACTION].toEnum(d.trai140Action),
            trai160Allow = p[TRAI_160_ALLOW] ?: d.trai160Allow,
            recentOutgoingDays = p[RECENT_OUTGOING_DAYS] ?: d.recentOutgoingDays,
            repeatCallerMinutes = p[REPEAT_CALLER_MINUTES] ?: d.repeatCallerMinutes,
            deriveMinCount = p[DERIVE_MIN_COUNT] ?: d.deriveMinCount,
            deriveMinPrefixDigits = p[DERIVE_MIN_PREFIX_DIGITS] ?: d.deriveMinPrefixDigits,
            listRefreshInterval = p[LIST_REFRESH_INTERVAL].toEnum(d.listRefreshInterval),
            listRefreshUnmeteredOnly = p[LIST_REFRESH_UNMETERED_ONLY] ?: d.listRefreshUnmeteredOnly,
            postCallPrompt = p[POST_CALL_PROMPT] ?: d.postCallPrompt,
            notifyOnBlock = p[NOTIFY_ON_BLOCK] ?: d.notifyOnBlock,
            importOffered = p[IMPORT_OFFERED] ?: d.importOffered,
            lastPromptedCallId = p[LAST_PROMPTED_CALL_ID] ?: d.lastPromptedCallId,
        )
    }

    private fun write(p: MutablePreferences, s: Settings) {
        p[BLOCK_HIDDEN] = s.blockHidden
        p[HIDDEN_ACTION] = s.hiddenAction.name
        p[BLOCK_INTERNATIONAL] = s.blockInternational
        p[INTERNATIONAL_ACTION] = s.internationalAction.name
        p[TRAI_140_ENABLED] = s.trai140Enabled
        p[TRAI_140_ACTION] = s.trai140Action.name
        p[TRAI_160_ALLOW] = s.trai160Allow
        p[RECENT_OUTGOING_DAYS] = s.recentOutgoingDays.coerceAtLeast(0)
        p[REPEAT_CALLER_MINUTES] = s.repeatCallerMinutes.coerceAtLeast(0)
        p[DERIVE_MIN_COUNT] = s.deriveMinCount.coerceAtLeast(2)
        p[DERIVE_MIN_PREFIX_DIGITS] = s.deriveMinPrefixDigits.coerceIn(6, PrefixDeriver.MAX_PREFIX_DIGITS)
        p[LIST_REFRESH_INTERVAL] = s.listRefreshInterval.name
        p[LIST_REFRESH_UNMETERED_ONLY] = s.listRefreshUnmeteredOnly
        p[POST_CALL_PROMPT] = s.postCallPrompt
        p[NOTIFY_ON_BLOCK] = s.notifyOnBlock
        p[IMPORT_OFFERED] = s.importOffered
        p[LAST_PROMPTED_CALL_ID] = s.lastPromptedCallId
    }

    private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
        this?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    private companion object {
        val BLOCK_HIDDEN = booleanPreferencesKey("blockHidden")
        val HIDDEN_ACTION = stringPreferencesKey("hiddenAction")
        val BLOCK_INTERNATIONAL = booleanPreferencesKey("blockInternational")
        val INTERNATIONAL_ACTION = stringPreferencesKey("internationalAction")
        val TRAI_140_ENABLED = booleanPreferencesKey("trai140Enabled")
        val TRAI_140_ACTION = stringPreferencesKey("trai140Action")
        val TRAI_160_ALLOW = booleanPreferencesKey("trai160Allow")
        val RECENT_OUTGOING_DAYS = intPreferencesKey("recentOutgoingDays")
        val REPEAT_CALLER_MINUTES = intPreferencesKey("repeatCallerMinutes")
        val DERIVE_MIN_COUNT = intPreferencesKey("deriveMinCount")
        val DERIVE_MIN_PREFIX_DIGITS = intPreferencesKey("deriveMinPrefixDigits")
        val LIST_REFRESH_INTERVAL = stringPreferencesKey("listRefreshInterval")
        val LIST_REFRESH_UNMETERED_ONLY = booleanPreferencesKey("listRefreshUnmeteredOnly")
        val POST_CALL_PROMPT = booleanPreferencesKey("postCallPrompt")
        val NOTIFY_ON_BLOCK = booleanPreferencesKey("notifyOnBlock")
        val IMPORT_OFFERED = booleanPreferencesKey("importOffered")
        val LAST_PROMPTED_CALL_ID = longPreferencesKey("lastPromptedCallId")
    }
}
