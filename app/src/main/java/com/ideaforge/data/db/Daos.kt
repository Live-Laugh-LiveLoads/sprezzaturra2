package com.ideaforge.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun get(id: Long): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observe(id: Long): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY createdAt DESC")
    suspend fun all(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE isRecording = 1")
    suspend fun recording(): List<SessionEntity>

    @Query("UPDATE sessions SET pipelineState = :state, stateMessage = :message WHERE id = :id")
    suspend fun setState(id: Long, state: String, message: String?)

    @Query("UPDATE sessions SET isRecording = :recording, durationMs = :durationMs WHERE id = :id")
    suspend fun setRecording(id: Long, recording: Boolean, durationMs: Long)

    @Query("UPDATE sessions SET audioDir = :dir WHERE id = :id")
    suspend fun setAudioDir(id: Long, dir: String)

    @Query("UPDATE sessions SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("UPDATE sessions SET userNotes = :notes WHERE id = :id")
    suspend fun setNotes(id: Long, notes: String)

    @Query("UPDATE sessions SET audioDeleted = 1 WHERE id = :id")
    suspend fun markAudioDeleted(id: Long)

    @Query("SELECT * FROM sessions WHERE audioDeleted = 0 AND isRecording = 0 AND createdAt < :before")
    suspend fun withAudioOlderThan(before: Long): List<SessionEntity>

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ChunkDao {
    @Insert
    suspend fun insert(chunk: ChunkEntity): Long

    @Query("SELECT * FROM chunks WHERE id = :id")
    suspend fun get(id: Long): ChunkEntity?

    @Query("SELECT * FROM chunks WHERE sessionId = :sessionId ORDER BY chunkIndex")
    suspend fun forSession(sessionId: Long): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE sessionId = :sessionId ORDER BY chunkIndex")
    fun observeForSession(sessionId: Long): Flow<List<ChunkEntity>>

    @Query("UPDATE chunks SET path = :path, durationMs = :durationMs, finalized = 1 WHERE id = :id")
    suspend fun finalize(id: Long, path: String, durationMs: Long)

    @Query("UPDATE chunks SET transcriptJson = :json WHERE id = :id")
    suspend fun setTranscript(id: Long, json: String?)

    @Query("DELETE FROM chunks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE chunks SET transcriptJson = NULL WHERE sessionId = :sessionId")
    suspend fun clearTranscripts(sessionId: Long)
}

@Dao
interface TranscriptDao {
    @Insert
    suspend fun insert(t: TranscriptEntity): Long

    @Query("SELECT * FROM transcripts WHERE sessionId = :sessionId AND kind = :kind ORDER BY version DESC LIMIT 1")
    suspend fun latest(sessionId: Long, kind: String): TranscriptEntity?

    @Query("SELECT * FROM transcripts WHERE sessionId = :sessionId AND kind = :kind ORDER BY version DESC LIMIT 1")
    fun observeLatest(sessionId: Long, kind: String): Flow<TranscriptEntity?>

    @Query("SELECT COALESCE(MAX(version), 0) FROM transcripts WHERE sessionId = :sessionId AND kind = :kind")
    suspend fun maxVersion(sessionId: Long, kind: String): Int

    @Query("SELECT * FROM transcripts WHERE sessionId = :sessionId ORDER BY kind, version")
    suspend fun allForSession(sessionId: Long): List<TranscriptEntity>

    /** Latest cleaned transcript per session whose text matches. */
    @Query(
        """SELECT t.* FROM transcripts t
           WHERE t.kind = 'CLEANED' AND t.text LIKE '%' || :q || '%'
             AND t.version = (SELECT MAX(version) FROM transcripts x WHERE x.sessionId = t.sessionId AND x.kind = 'CLEANED')
           ORDER BY t.createdAt DESC LIMIT 50""",
    )
    suspend fun search(q: String): List<TranscriptEntity>
}

@Dao
interface StageDao {
    @Upsert
    suspend fun upsert(stage: StageRunEntity)

    @Query("SELECT * FROM stage_runs WHERE sessionId = :sessionId")
    suspend fun forSession(sessionId: Long): List<StageRunEntity>

    @Query("SELECT * FROM stage_runs WHERE sessionId = :sessionId")
    fun observeForSession(sessionId: Long): Flow<List<StageRunEntity>>

    @Query("SELECT * FROM stage_runs")
    fun observeAll(): Flow<List<StageRunEntity>>

    @Query("DELETE FROM stage_runs WHERE sessionId = :sessionId AND stage = :stage AND round = :round")
    suspend fun delete(sessionId: Long, stage: String, round: Int)

    /** Marks stages left RUNNING by a killed process as PENDING so the next run redoes them. */
    @Query("UPDATE stage_runs SET status = 'PENDING', note = 'Interrupted; will resume' WHERE status = 'RUNNING'")
    suspend fun resetInterrupted()
}

@Dao
abstract class IdeaDao {
    @Insert
    abstract suspend fun insertAll(ideas: List<IdeaEntity>)

    @Query("DELETE FROM ideas WHERE sessionId = :sessionId AND round = :round")
    abstract suspend fun deleteRound(sessionId: Long, round: Int)

    @Transaction
    open suspend fun replaceRound(sessionId: Long, round: Int, ideas: List<IdeaEntity>) {
        deleteRound(sessionId, round)
        insertAll(ideas)
    }

    @Query("SELECT * FROM ideas WHERE sessionId = :sessionId ORDER BY round, COALESCE(shortlistRank, 9999), id")
    abstract fun observeForSession(sessionId: Long): Flow<List<IdeaEntity>>

    @Query(
        """SELECT * FROM ideas i WHERE (i.title LIKE '%' || :q || '%' OR i.description LIKE '%' || :q || '%')
           AND i.round = (SELECT MAX(round) FROM ideas x WHERE x.sessionId = i.sessionId)
           ORDER BY i.id DESC LIMIT 50""",
    )
    abstract suspend fun search(q: String): List<IdeaEntity>
}

@Dao
abstract class PlanDao {
    @Insert
    abstract suspend fun insertAll(plans: List<PlanEntity>)

    @Query("DELETE FROM plans WHERE sessionId = :sessionId AND status = 'PENDING'")
    abstract suspend fun deletePending(sessionId: Long)

    @Transaction
    open suspend fun replacePending(sessionId: Long, plans: List<PlanEntity>) {
        deletePending(sessionId)
        insertAll(plans)
    }

    @Query("SELECT * FROM plans WHERE id = :id")
    abstract suspend fun get(id: Long): PlanEntity?

    @Query("SELECT * FROM plans WHERE sessionId = :sessionId ORDER BY rank")
    abstract suspend fun forSession(sessionId: Long): List<PlanEntity>

    @Query("SELECT * FROM plans WHERE sessionId = :sessionId ORDER BY rank")
    abstract fun observeForSession(sessionId: Long): Flow<List<PlanEntity>>

    @Query("UPDATE plans SET status = :status, statusMessage = :message WHERE id = :id")
    abstract suspend fun setStatus(id: Long, status: String, message: String?)

    @Query("UPDATE plans SET status = :status, rejectReason = :reason, decidedAt = :at WHERE id = :id")
    abstract suspend fun decide(id: Long, status: String, reason: String?, at: Long)

    @Query("UPDATE plans SET editedPlanJson = :json, title = :title, summary = :summary WHERE id = :id")
    abstract suspend fun saveEdit(id: Long, json: String, title: String, summary: String)
}

@Dao
interface DeliverableDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(d: DeliverableEntity): Long

    @Query("SELECT * FROM deliverables WHERE id = :id")
    suspend fun get(id: Long): DeliverableEntity?

    @Query("SELECT * FROM deliverables WHERE planId = :planId ORDER BY id")
    suspend fun forPlan(planId: Long): List<DeliverableEntity>

    @Query("SELECT * FROM deliverables WHERE sessionId = :sessionId ORDER BY planId, id")
    fun observeForSession(sessionId: Long): Flow<List<DeliverableEntity>>

    @Query("SELECT * FROM deliverables WHERE sessionId = :sessionId ORDER BY planId, id")
    suspend fun forSession(sessionId: Long): List<DeliverableEntity>
}

@Dao
interface UsageDao {
    @Insert
    suspend fun insert(u: UsageEntity)

    @Query("SELECT COALESCE(SUM(costUsd), 0) FROM usage WHERE atEpochMs >= :from")
    suspend fun costSince(from: Long): Double

    @Query("SELECT COALESCE(SUM(costUsd), 0) FROM usage WHERE atEpochMs >= :from")
    fun observeCostSince(from: Long): Flow<Double>

    @Query("SELECT * FROM usage WHERE sessionId = :sessionId ORDER BY atEpochMs")
    fun observeForSession(sessionId: Long): Flow<List<UsageEntity>>

    @Query("SELECT * FROM usage WHERE sessionId = :sessionId ORDER BY atEpochMs")
    suspend fun forSession(sessionId: Long): List<UsageEntity>
}

@Dao
interface SpeakerDao {
    @Upsert
    suspend fun upsert(s: SpeakerEntity)

    @Query("SELECT * FROM speakers WHERE sessionId = :sessionId")
    suspend fun forSession(sessionId: Long): List<SpeakerEntity>

    @Query("SELECT * FROM speakers WHERE sessionId = :sessionId")
    fun observeForSession(sessionId: Long): Flow<List<SpeakerEntity>>
}

@Dao
interface SessionEventDao {
    @Insert
    suspend fun insert(e: SessionEventEntity)

    @Query("SELECT * FROM session_events WHERE sessionId = :sessionId ORDER BY atEpochMs")
    fun observeForSession(sessionId: Long): Flow<List<SessionEventEntity>>

    @Query("SELECT * FROM session_events WHERE sessionId = :sessionId ORDER BY atEpochMs")
    suspend fun forSession(sessionId: Long): List<SessionEventEntity>
}
