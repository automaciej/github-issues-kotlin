package pl.blizinski.githubissuesstore

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.serializer
import pl.blizinski.githubissuesstore.internal.GitHubRepo
import pl.blizinski.githubissuesstore.internal.GitHubSyncErrorClassifier
import pl.blizinski.githubissuesstore.internal.GitHubTask
import pl.blizinski.githubissuesstore.internal.network.GitHubIssuesNetworkSource
import pl.blizinski.githubissuesstore.internal.toPublic
import pl.blizinski.githubissuesstore.internal.toTask
import pl.blizinski.githubissuesstore.internal.toTaskList
import pl.blizinski.githubissuesstore.models.FatalStorageError
import pl.blizinski.githubissuesstore.models.SyncStatus
import pl.blizinski.githubissuesstore.models.Task
import pl.blizinski.githubissuesstore.models.TaskList
import pl.blizinski.tasksync.AdaptivePoller
import pl.blizinski.tasksync.OpType
import pl.blizinski.tasksync.PendingOp
import pl.blizinski.tasksync.PendingOpsProcessor
import pl.blizinski.tasksync.RoomLocalStore
import pl.blizinski.tasksync.SyncConfig
import pl.blizinski.tasksync.SyncEngine
import pl.blizinski.tasksync.SyncWorkerDependencies
import pl.blizinski.tasksync.SyncedRecord
import pl.blizinski.tasksync.accumulateRecentErrors
import pl.blizinski.tasksync.db.TaskSyncDatabase
import pl.blizinski.tasksync.isNetworkAvailable
import java.io.Closeable
import java.util.UUID
import kotlinx.serialization.json.Json

private const val TAG = "GitHubIssuesStore"

/**
 * Local-first store for GitHub Issues. Reads always come from the Room cache; writes are applied
 * locally and queued for background sync. The library manages all network interaction
 * internally, except token acquisition (see [tokenProvider]).
 *
 * Every repository the connected token can see syncs automatically — [taskLists] mirrors
 * `GET /user/repos` the same way [pl.blizinski.microsofttodostore.MicrosoftToDoStore]/
 * `GoogleTasksStore` mirror their own sources' full list set. There is no in-library "add/remove
 * repo" concept; which lists are actually used is Task Compass's own enable/disable-list
 * concern, not this library's (see [GitHubIssuesStoreApi]'s doc comment and the design doc's
 * Course Correction log entry). Only one instance per connected GitHub account is supported per
 * process — each account gets its own [GitHubIssuesStore] instance with its own
 * [config]-supplied database file name.
 */
class GitHubIssuesStore(
    context: Context,
    private val tokenProvider: GitHubAccessTokenProvider,
    private val config: GitHubIssuesStoreConfig = GitHubIssuesStoreConfig(),
) : GitHubIssuesStoreApi, Closeable {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    private val db: TaskSyncDatabase = Room.databaseBuilder(
        appContext,
        TaskSyncDatabase::class.java,
        config.dbName,
    ).build() // fresh schema, no legacy on-disk format to migrate from

    private val store = RoomLocalStore<GitHubTask, GitHubRepo>(
        db.recordsDao(),
        db.listsDao(),
        db.pendingOpsDao(),
        serializer(),
        serializer(),
    )

    private val network = GitHubIssuesNetworkSource(tokenProvider = tokenProvider)
    private val errorClassifier = GitHubSyncErrorClassifier()
    private val pendingOpsProcessor = PendingOpsProcessor(store, network, serializer<GitHubTask>(), errorClassifier)
    private val syncEngine = SyncEngine(
        store, network, pendingOpsProcessor, errorClassifier,
        isOnline = { isNetworkAvailable(appContext) },
    )

    private val syncConfig = SyncConfig(config.minPollInterval, config.maxPollInterval)
    private val workManager = WorkManager.getInstance(appContext)
    private val poller = AdaptivePoller(workManager, syncConfig, instanceKey = config.dbName)

    private val _syncStatus = MutableStateFlow(SyncStatus())

    init {
        SyncWorkerDependencies.put(
            config.dbName,
            SyncWorkerDependencies.Deps(syncEngine, syncConfig, onSyncResult = ::applySyncResult),
        )
        poller.start()
    }

    private fun applySyncResult(result: SyncEngine.SyncResult) {
        val now = System.currentTimeMillis()
        _syncStatus.update { current ->
            current.copy(
                isSyncing = false,
                lastSyncedAt = now,
                recentErrors = accumulateRecentErrors(
                    previous = current.recentErrors,
                    new = result.errors.map { it.toPublic() },
                    max = config.maxRecentErrors,
                ),
                consentIntent = result.consentIntent,
            )
        }
    }

    private fun reportFatalStorageError(e: Throwable) {
        Log.e(TAG, "Local storage unusable", e)
        _syncStatus.update { current ->
            if (current.fatalStorageError != null) current
            else current.copy(
                fatalStorageError = FatalStorageError(
                    occurredAt = System.currentTimeMillis(),
                    summary = e.message ?: e::class.simpleName ?: "Unknown error",
                    details = e.stackTraceToString(),
                )
            )
        }
    }

    private fun <T> Flow<T>.guardStorage(default: T): Flow<T> = catch { e ->
        reportFatalStorageError(e)
        emit(default)
    }

    // -----------------------------------------------------------------------
    // Public read API
    // -----------------------------------------------------------------------

    override fun taskLists(): Flow<List<TaskList>> =
        store.lists().guardStorage(emptyList()).map { lists -> lists.map { it.toTaskList() } }

    override fun tasks(listLocalId: String): Flow<List<Task>> =
        store.records(listLocalId).guardStorage(emptyList()).map { records -> records.map { it.toTask() } }

    override fun syncStatus(): Flow<SyncStatus> = combine(
        _syncStatus,
        store.pendingOpCount().guardStorage(0),
        store.failedOpCount().guardStorage(0),
    ) { status, pending, failed -> status.copy(pendingOpCount = pending, failedOpCount = failed) }

    /** See [SyncEngine.writeMutex]'s doc comment — every local write must hold it. */
    private suspend fun <T> guardWrite(onError: T, block: suspend () -> T): T = try {
        syncEngine.writeMutex.withLock { block() }
    } catch (e: Exception) {
        reportFatalStorageError(e)
        onError
    }

    // -----------------------------------------------------------------------
    // Public write API — optimistic local write + pending op + trigger sync
    // -----------------------------------------------------------------------

    override suspend fun createTask(listLocalId: String, title: String, notes: String?): String = guardWrite(onError = "") {
        val localId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val content = GitHubTask(title = title, notes = notes, createdDate = now)
        store.upsertRecord(
            SyncedRecord(localId = localId, remoteId = null, listLocalId = listLocalId, content = content, isCompleted = false, lastSyncedAt = null)
        )
        store.enqueuePendingOp(
            PendingOp(
                id = UUID.randomUUID().toString(), type = OpType.CREATE_RECORD, entityLocalId = localId, listLocalId = listLocalId,
                contentJson = json.encodeToString(serializer(), content), createdAt = now,
            )
        )
        poller.onLocalWrite()
        localId
    }

    override suspend fun updateTask(localId: String, title: String, notes: String?): Unit = guardWrite(onError = Unit) {
        val entity = store.getRecordByLocalId(localId) ?: return@guardWrite
        val now = System.currentTimeMillis()
        val newContent = entity.content.copy(title = title, notes = notes)
        store.upsertRecord(entity.copy(content = newContent))
        store.enqueuePendingOp(
            PendingOp(
                id = UUID.randomUUID().toString(), type = OpType.UPDATE_RECORD, entityLocalId = localId, listLocalId = entity.listLocalId,
                contentJson = json.encodeToString(serializer(), newContent), createdAt = now,
            )
        )
        poller.onLocalWrite()
    }

    override suspend fun completeTask(localId: String): Unit = guardWrite(onError = Unit) {
        val entity = store.getRecordByLocalId(localId) ?: return@guardWrite
        val now = System.currentTimeMillis()
        store.upsertRecord(entity.copy(isCompleted = true, content = entity.content.copy(completedDate = now)))
        store.enqueuePendingOp(
            PendingOp(id = UUID.randomUUID().toString(), type = OpType.COMPLETE_RECORD, entityLocalId = localId, listLocalId = entity.listLocalId, createdAt = now)
        )
        poller.onLocalWrite()
    }

    override suspend fun uncompleteTask(localId: String): Unit = guardWrite(onError = Unit) {
        val entity = store.getRecordByLocalId(localId) ?: return@guardWrite
        val now = System.currentTimeMillis()
        store.upsertRecord(entity.copy(isCompleted = false, content = entity.content.copy(completedDate = null)))
        store.enqueuePendingOp(
            PendingOp(id = UUID.randomUUID().toString(), type = OpType.UNCOMPLETE_RECORD, entityLocalId = localId, listLocalId = entity.listLocalId, createdAt = now)
        )
        poller.onLocalWrite()
    }

    /** See this class's doc comment and [GitHubIssuesStoreApi.deleteTask] — closes the issue
     *  with `state_reason: not_planned` rather than a true delete, which GitHub's REST API
     *  doesn't support. */
    override suspend fun deleteTask(localId: String): Unit = guardWrite(onError = Unit) {
        val entity = store.getRecordByLocalId(localId) ?: return@guardWrite
        val now = System.currentTimeMillis()
        store.softDeleteRecord(localId)
        store.enqueuePendingOp(
            PendingOp(id = UUID.randomUUID().toString(), type = OpType.DELETE_RECORD, entityLocalId = localId, listLocalId = entity.listLocalId, createdAt = now)
        )
        poller.onLocalWrite()
    }

    override suspend fun forceSync() {
        _syncStatus.update { it.copy(isSyncing = true, consentIntent = null) }
        try {
            applySyncResult(syncEngine.sync())
        } catch (e: Exception) {
            reportFatalStorageError(e)
            _syncStatus.update { it.copy(isSyncing = false) }
        }
    }

    override suspend fun fullSync() {
        _syncStatus.update { it.copy(isSyncing = true, consentIntent = null) }
        try {
            applySyncResult(syncEngine.fullSync())
        } catch (e: Exception) {
            reportFatalStorageError(e)
            _syncStatus.update { it.copy(isSyncing = false) }
        }
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    override fun close() {
        poller.cancel()
        db.close()
        SyncWorkerDependencies.remove(config.dbName)
    }
}
