package uk.co.cricrelay.mobile.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import uk.co.cricrelay.mobile.database.CricRelayDatabase
import uk.co.cricrelay.mobile.database.StreamDao
import uk.co.cricrelay.shared.repository.ApiClientProvider
import uk.co.cricrelay.shared.repository.AuthRepository
import uk.co.cricrelay.shared.repository.DefaultApiClientProvider
import uk.co.cricrelay.shared.repository.StreamRepository
import uk.co.cricrelay.shared.session.SessionStore
import uk.co.cricrelay.stream.StreamController
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideSessionStore(@ApplicationContext context: Context): SessionStore =
        SessionStore(context)

    @Provides
    @Singleton
    fun provideAuthRepository(sessionStore: SessionStore): AuthRepository =
        AuthRepository(sessionStore)

    @Provides
    @Singleton
    fun provideApiClientProvider(authRepository: AuthRepository): ApiClientProvider =
        DefaultApiClientProvider(authRepository)

    @Provides
    @Singleton
    fun provideStreamRepository(apiClientProvider: ApiClientProvider): StreamRepository =
        StreamRepository(apiClientProvider)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): CricRelayDatabase =
        Room.databaseBuilder(context, CricRelayDatabase::class.java, "cricrelay.db").build()

    @Provides
    fun provideStreamDao(database: CricRelayDatabase): StreamDao = database.streamDao()

    @Provides
    @Singleton
    fun provideStreamController(): StreamController = StreamController()
}
