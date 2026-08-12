package pl.blizinski.githubissuesstore

import kotlinx.coroutines.flow.Flow
import pl.blizinski.githubissuesstore.models.SyncStatus
import pl.blizinski.githubissuesstore.models.Task
import pl.blizinski.githubissuesstore.models.TaskList

/**
 * Public contract for the GitHub Issues local store. Implemented by [GitHubIssuesStore]; can be
 * faked in tests without Android framework deps.
 *
 * There is no `createList`/`updateList`/`deleteList` here — a GitHub "list" is a pre-existing
 * repository this library neither creates, renames, nor deletes. Unlike an earlier draft of this
 * design, there is also no explicit "add repo" surface: every repository the connected token can
 * see syncs automatically ([taskLists] mirrors `GET /user/repos` directly, the same shape as
 * [pl.blizinski.microsofttodostore.MicrosoftToDoStoreApi]'s Google/Microsoft equivalents) —
 * *which* of those lists a user actually wants is Task Compass's own enable/disable-list and
 * workspace-membership concern, not this library's. See the design doc's Course Correction log
 * entry.
 */
interface GitHubIssuesStoreApi {

    // --- Read ---

    fun taskLists(): Flow<List<TaskList>>

    /** [listLocalId] is the [TaskList.id] returned by [taskLists]. */
    fun tasks(listLocalId: String): Flow<List<Task>>

    fun syncStatus(): Flow<SyncStatus>

    // --- Write (optimistic — applied locally, synced in background) ---

    /** Creates a task (a GitHub issue) and returns its stable [Task.id] localId. */
    suspend fun createTask(listLocalId: String, title: String, notes: String? = null): String

    /** Updates title and notes (issue body). */
    suspend fun updateTask(localId: String, title: String, notes: String?)

    /** Closes the issue with `state_reason: completed`. */
    suspend fun completeTask(localId: String)

    /** Reopens the issue with `state_reason: reopened`. */
    suspend fun uncompleteTask(localId: String)

    /**
     * GitHub's REST API has no issue-delete endpoint. This closes the issue with
     * `state_reason: not_planned` (a distinct reason from [completeTask]'s `completed`) and
     * removes it from this library's own local tracking — see the design doc's Key Design
     * Decisions. Not exposed as a direct user-facing action in TaskCompass (see
     * `SourceCapabilities.supportsManualDelete`); still used internally for the
     * no-native-cross-list-move fallback (create-in-new-list + delete-old-list).
     */
    suspend fun deleteTask(localId: String)

    // --- Lifecycle ---

    /** Runs a full sync cycle synchronously (flush pending ops, then pull). */
    suspend fun forceSync()

    /** Like [forceSync], but pulls every list from scratch instead of using each list's stored
     *  incremental-sync cursor. */
    suspend fun fullSync()

    /** Cancels background work and closes the database. */
    fun close()
}
