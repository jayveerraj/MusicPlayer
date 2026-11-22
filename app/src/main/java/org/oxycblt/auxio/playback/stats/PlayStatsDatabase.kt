/*
 * Copyright (c) 2026 Auxio Project
 * PlayStatsDatabase.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.playback.stats

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.oxycblt.musikr.Music

/** Stores how often and how recently each song was played. */
@Database(entities = [PlayStat::class], version = 1, exportSchema = false)
@TypeConverters(Music.UID.TypeConverters::class)
abstract class PlayStatsDatabase : RoomDatabase() {
    abstract fun playStatsDao(): PlayStatsDao
}

@Entity data class PlayStat(@PrimaryKey val uid: Music.UID, val count: Int, val lastPlayedMs: Long)

@Dao
interface PlayStatsDao {
    @Query("SELECT * FROM PlayStat") suspend fun getAll(): List<PlayStat>

    @Query("SELECT * FROM PlayStat WHERE uid = :uid") suspend fun get(uid: Music.UID): PlayStat?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(stat: PlayStat)
}

@Module
@InstallIn(SingletonComponent::class)
class PlayStatsRoomModule {
    @Singleton
    @Provides
    fun database(@ApplicationContext context: Context) =
        Room.databaseBuilder(
                context.applicationContext,
                PlayStatsDatabase::class.java,
                "play_stats.db",
            )
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun playStatsDao(database: PlayStatsDatabase) = database.playStatsDao()
}
