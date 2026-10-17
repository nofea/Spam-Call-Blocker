package com.sheen.spamblocker.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

@Database(
    entities = [
        BlockRule::class,
        AllowRule::class,
        ListSource::class,
        ListEntry::class,
        CallEvent::class,
        DerivedSuggestion::class,
        FalsePositive::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class SpamBlockerDatabase : RoomDatabase() {
    abstract fun blockRules(): BlockRuleDao
    abstract fun allowRules(): AllowRuleDao
    abstract fun listSources(): ListSourceDao
    abstract fun listEntries(): ListEntryDao
    abstract fun callEvents(): CallEventDao
    abstract fun suggestions(): DerivedSuggestionDao
    abstract fun falsePositives(): FalsePositiveDao

    companion object {
        const val NAME = "spamblocker.db"

        /** Tables whose changes require a [com.sheen.spamblocker.rules.RuleIndex] rebuild. */
        val RULE_TABLES = arrayOf("BlockRule", "AllowRule", "ListSource", "ListEntry")
    }
}

class Converters {
    // Numbers never contain newlines, so a newline-separated string is enough.
    @TypeConverter
    fun fromList(value: List<String>): String = value.joinToString("\n")

    @TypeConverter
    fun toList(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split("\n")
}
