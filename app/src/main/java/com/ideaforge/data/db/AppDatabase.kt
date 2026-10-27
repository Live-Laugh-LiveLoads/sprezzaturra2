package com.ideaforge.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        SessionEntity::class,
        ChunkEntity::class,
        TranscriptEntity::class,
        StageRunEntity::class,
        IdeaEntity::class,
        PlanEntity::class,
        DeliverableEntity::class,
        UsageEntity::class,
        SpeakerEntity::class,
        SessionEventEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun chunks(): ChunkDao
    abstract fun transcripts(): TranscriptDao
    abstract fun stages(): StageDao
    abstract fun ideas(): IdeaDao
    abstract fun plans(): PlanDao
    abstract fun deliverables(): DeliverableDao
    abstract fun usage(): UsageDao
    abstract fun speakers(): SpeakerDao
    abstract fun events(): SessionEventDao

    companion object {
        const val NAME = "ideaforge.db"
    }
}
