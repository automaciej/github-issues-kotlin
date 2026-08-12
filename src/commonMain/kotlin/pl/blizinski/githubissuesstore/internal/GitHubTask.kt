package pl.blizinski.githubissuesstore.internal

import kotlinx.serialization.Serializable

/**
 * Opaque content type for [pl.blizinski.tasksync.SyncEngine]/[pl.blizinski.tasksync.PendingOpsProcessor]
 * — everything about a GitHub issue except the fields promoted into the shared sync envelope
 * (localId, remoteId, listLocalId, isCompleted, isDeleted, lastSyncedAt, remoteUpdatedAt).
 * No due date/priority fields — GitHub Issues has neither natively (see the design doc's
 * capability table); a `Reminder` sourced from this library must never fabricate either.
 */
@Serializable
internal data class GitHubTask(
    val title: String,
    val notes: String? = null,
    val createdDate: Long? = null,
    val completedDate: Long? = null,
    val labels: List<String> = emptyList(),
)

@Serializable
internal data class GitHubRepo(
    val owner: String,
    val name: String,
    val description: String? = null,
    val isPrivate: Boolean = false,
)
