package com.sheen.spamblocker.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.sheen.spamblocker.data.SpamBlockerDatabase
import com.sheen.spamblocker.lists.ListParser
import com.sheen.spamblocker.phone.PhoneNormalizer
import com.sheen.spamblocker.rules.WildcardCompiler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** Process-wide scope for work that outlives a screen or a service call. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @AppScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): SpamBlockerDatabase =
        Room.databaseBuilder(context, SpamBlockerDatabase::class.java, SpamBlockerDatabase.NAME).build()

    @Provides fun blockRules(db: SpamBlockerDatabase) = db.blockRules()
    @Provides fun allowRules(db: SpamBlockerDatabase) = db.allowRules()
    @Provides fun listSources(db: SpamBlockerDatabase) = db.listSources()
    @Provides fun listEntries(db: SpamBlockerDatabase) = db.listEntries()
    @Provides fun callEvents(db: SpamBlockerDatabase) = db.callEvents()
    @Provides fun suggestions(db: SpamBlockerDatabase) = db.suggestions()
    @Provides fun falsePositives(db: SpamBlockerDatabase) = db.falsePositives()

    @Provides
    @Singleton
    fun settingsStore(@ApplicationContext context: Context): DataStore<Preferences> = context.settingsStore

    /** libphonenumber loads its metadata once; share one instance. */
    @Provides
    @Singleton
    fun phoneNormalizer(): PhoneNormalizer = PhoneNormalizer()

    @Provides
    fun wildcardCompiler(normalizer: PhoneNormalizer) = WildcardCompiler(normalizer)

    @Provides
    fun listParser(normalizer: PhoneNormalizer) = ListParser(normalizer)

    /** Only used to fetch the user's list URLs (SPEC §3). */
    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
}
