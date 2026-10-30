package ru.voiceassistant.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import ru.voiceassistant.data.local.AppDatabase
import ru.voiceassistant.data.remote.AssistantApi
import ru.voiceassistant.data.repository.AppJson
import ru.voiceassistant.data.repository.AssistantRepository
import ru.voiceassistant.data.repository.ConversationRepository
import ru.voiceassistant.data.repository.DataStoreSettingsRepository
import ru.voiceassistant.data.repository.ExpenseRepository
import ru.voiceassistant.data.repository.NoteRepository
import ru.voiceassistant.data.repository.ReminderRepository
import ru.voiceassistant.data.repository.RemoteAssistantRepository
import ru.voiceassistant.data.repository.RoomConversationRepository
import ru.voiceassistant.data.repository.RoomExpenseRepository
import ru.voiceassistant.data.repository.RoomNoteRepository
import ru.voiceassistant.data.repository.RoomReminderRepository
import ru.voiceassistant.data.repository.RoomTaskRepository
import ru.voiceassistant.data.repository.SettingsRepository
import ru.voiceassistant.data.repository.TaskRepository
import ru.voiceassistant.service.AlarmScheduler
import ru.voiceassistant.service.AndroidAlarmScheduler
import ru.voiceassistant.util.SystemTimeProvider
import ru.voiceassistant.util.TimeProvider
import ru.voiceassistant.wakeword.PorcupineWakeWordDetector
import ru.voiceassistant.wakeword.WakeWordDetector
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME).build()

    @Provides fun taskDao(db: AppDatabase) = db.taskDao()
    @Provides fun noteDao(db: AppDatabase) = db.noteDao()
    @Provides fun expenseDao(db: AppDatabase) = db.expenseDao()
    @Provides fun reminderDao(db: AppDatabase) = db.reminderDao()
    @Provides fun conversationDao(db: AppDatabase) = db.conversationDao()
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // Логирование тел запросов намеренно не подключено: там личные данные.
        .build()

    @Provides
    @Singleton
    fun api(client: OkHttpClient): AssistantApi = Retrofit.Builder()
        .baseUrl("http://localhost/") // заглушка: реальный адрес передаётся через @Url
        .client(client)
        .addConverterFactory(AppJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(AssistantApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds abstract fun settings(impl: DataStoreSettingsRepository): SettingsRepository
    @Binds abstract fun assistant(impl: RemoteAssistantRepository): AssistantRepository
    @Binds abstract fun tasks(impl: RoomTaskRepository): TaskRepository
    @Binds abstract fun notes(impl: RoomNoteRepository): NoteRepository
    @Binds abstract fun expenses(impl: RoomExpenseRepository): ExpenseRepository
    @Binds abstract fun reminders(impl: RoomReminderRepository): ReminderRepository
    @Binds abstract fun conversations(impl: RoomConversationRepository): ConversationRepository
    @Binds abstract fun alarms(impl: AndroidAlarmScheduler): AlarmScheduler
    @Binds abstract fun time(impl: SystemTimeProvider): TimeProvider
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    @AppScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun wakeWordDetector(@ApplicationContext context: Context): WakeWordDetector = PorcupineWakeWordDetector(context)
}
