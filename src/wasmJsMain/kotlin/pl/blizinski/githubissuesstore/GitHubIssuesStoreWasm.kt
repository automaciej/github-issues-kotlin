package pl.blizinski.githubissuesstore

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import pl.blizinski.githubissuesstore.internal.GitHubRepo
import pl.blizinski.githubissuesstore.internal.GitHubSyncErrorClassifierWasm
import pl.blizinski.githubissuesstore.internal.GitHubTask
import pl.blizinski.githubissuesstore.internal.network.GitHubIssuesNetworkSourceWasm
import pl.blizinski.githubissuesstore.internal.toPublic
import pl.blizinski.githubissuesstore.internal.toTask
import pl.blizinski.githubissuesstore.internal.toTaskList
import pl.blizinski.githubissuesstore.models.SyncStatus
import pl.blizinski.githubissuesstore.models.Task
import pl.blizinski.githubissuesstore.models.TaskList
import pl.blizinski.tasksync.IndexedDbLocalStore
import pl.blizinski.tasksync.OpType
import pl.blizinski.tasksync.PendingOp
import pl.blizinski.tasksync.PendingOpsProcessor
import pl.blizinski.tasksync.SyncEngine
import pl.blizinski.tasksync.SyncedRecord
import pl.blizinski.tasksync.accumulateRecentErrors

/**
 * wasmJs [GitHubIssuesStoreApi] implementation — see TaskCompass's
 * Docs/designs/2026-07-30-web-wasmjs-google-tasks-poc.md (Stage G).
 *
 * Structurally mirrors [pl.blizinski.googletasksstore.GoogleTasksStoreWasm] and the Android
 * target's `GitHubIssuesStore`: wires the *same* [SyncEngine]/[PendingOpsProcessor] Android uses
 * into an [IndexedDbLocalStore] (persisted across page reloads via IndexedDB — see
 * TaskCompass's Docs/designs/2026-08-02-web-indexeddb-persistence.md) and
 * [GitHubIssuesNetworkSourceWasm] (Ktor, no OkHttp). No
 * [pl.blizinski.tasksync.AdaptivePoller]/WorkManager — syncs on demand only.
 */
@OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
class GitHubIssuesStoreWasm(
    tokenProvider: GitHubAccessTokenProvider,
    private val config: GitHubIssuesStoreConfig = GitHubIssuesStoreConfig(),
) : GitHubIssuesStoreApi {

    private val json = Json { ignoreUnknownKeys = true }
    private val store = IndexedDbLocalStore(config.dbName, serializer<GitHubTask>(), serializer<GitHubRepo>())
    private val network = GitHubIssuesNetworkSourceWasm(tokenProvider)
    private val errorClassifier = GitHubSyncErrorClassifierWasm()
    private val pendingOpsProcessor = PendingOpsProcessor(store, network, serializer<GitHubTask>(), errorClassifier)
    private val syncEngine = SyncEngine(store, network, pendingOpsProcessor, errorClassifier)

    private val _syncStatus = MutableStateFlow(SyncStatus())

    private fun applySyncResult(result: SyncEngine.SyncResult) {
        val now = Clock.System.now().toEpochMilliseconds()
        _syncStatus.value = _syncStatus.value.copy(
            isSyncing = false,
            lastSyncedAt = now,
            recentErrors = accumulateRecentErrors(
                previous = _syncStatus.value.recentErrors,
                new = result.errors.map { it.toPublic() },
                max = config.maxRecentErrors,
            ),
        )
    }

    // -----------------------------------------------------------------------
    // Public read API
    // -----------------------------------------------------------------------

    override fun taskLists(): Flow<List<TaskList>> =
        store.lists().map { lists -> lists.map { it.toTaskList() } }

    override fun tasks(listLocalId: String): Flow<List<Task>> =
        store.records(listLocalId).map { records -> records.map { it.toTask() } }

    override fun syncStatus(): Flow<SyncStatus> = combine(
        _syncStatus,
        store.pendingOpCount(),
        store.failedOpCount(),
    ) { status, pending, failed -> status.copy(pendingOpCount = pending, failedOpCount = failed) }

    // -----------------------------------------------------------------------
    // Public write API — optimistic local write + pending op
    // -----------------------------------------------------------------------

    private suspend fun <T> guardWrite(block: suspend () -> T): T = syncEngine.writeMutex.withLock { block() }

    override suspend fun createTask(listLocalId: String, title: String, notes: String?): String = guardWrite {
        val localId = Uuid.random().toString()
        val now = Clock.System.now().toEpochMilliseconds()
        val content = GitHubTask(title = title, notes = notes, createdDate = now)
        store.upsertRecord(
            SyncedRecord(localId = localId, remoteId = null, listLocalId = listLocalId, content = content, isCompleted = false, lastSyncedAt = null)
        )
        store.enqueuePendingOp(
            PendingOp(
                id = Uuid.random().toString(), type = OpType.CREATE_RECORD, entityLocalId = localId, listLocalId = listLocalId,
                contentJson = json.encodeToString(serializer(), content), createdAt = now,
            )
        )
        localId
    }

    override suspend fun updateTask(localId: String, title: String, notes: String?): Unit = guardWrite {
        val entity = store.getRecordByLocalId(localId) ?: return@guardWrite
        val now = Clock.System.now().toEpochMilliseconds()
        val newContent = entity.content.copy(title = title, notes = notes)
        store.upsertRecord(entity.copy(content = newContent))
        store.enqueuePendingOp(
            PendingOp(
                id = Uuid.random().toString(), type = OpType.UPDATE_RECORD, entityLocalId = localId, listLocalId = entity.listLocalId,
                contentJson = json.encodeToString(serializer(), newContent), createdAt = now,
            )
        )
    }

    override suspend fun completeTask(localId: String): Unit = guardWrite {
        val entity = store.getRecordByLocalId(localId) ?: return@guardWrite
        val now = Clock.System.now().toEpochMilliseconds()
        store.upsertRecord(entity.copy(isCompleted = true, content = entity.content.copy(completedDate = now)))
        store.enqueuePendingOp(PendingOp(id = Uuid.random().toString(), type = OpType.COMPLETE_RECORD, entityLocalId = localId, listLocalId = entity.listLocalId, createdAt = now))
    }

    override suspend fun uncompleteTask(localId: String): Unit = guardWrite {
        val entity = store.getRecordByLocalId(localId) ?: return@guardWrite
        val now = Clock.System.now().toEpochMilliseconds()
        store.upsertRecord(entity.copy(isCompleted = false, content = entity.content.copy(completedDate = null)))
        store.enqueuePendingOp(PendingOp(id = Uuid.random().toString(), type = OpType.UNCOMPLETE_RECORD, entityLocalId = localId, listLocalId = entity.listLocalId, createdAt = now))
    }

    override suspend fun deleteTask(localId: String): Unit = guardWrite {
        val entity = store.getRecordByLocalId(localId) ?: return@guardWrite
        val now = Clock.System.now().toEpochMilliseconds()
        store.softDeleteRecord(localId)
        store.enqueuePendingOp(PendingOp(id = Uuid.random().toString(), type = OpType.DELETE_RECORD, entityLocalId = localId, listLocalId = entity.listLocalId, createdAt = now))
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    override suspend fun forceSync() {
        _syncStatus.value = _syncStatus.value.copy(isSyncing = true)
        applySyncResult(syncEngine.sync())
    }

    override suspend fun fullSync() {
        _syncStatus.value = _syncStatus.value.copy(isSyncing = true)
        applySyncResult(syncEngine.fullSync())
    }

    override fun close() {
        // No background work, no database — nothing to release for this PoC.
    }
}
