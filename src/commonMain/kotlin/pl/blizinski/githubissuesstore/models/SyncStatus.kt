package pl.blizinski.githubissuesstore.models

data class SyncStatus(
    val isSyncing: Boolean = false,
    val lastSyncedAt: Long? = null,
    val pendingOpCount: Int = 0,
    val failedOpCount: Int = 0,
    val recentErrors: List<SyncError> = emptyList(),
    /**
     * Always null for this source — a PAT has no interactive consent/re-auth flow the way
     * Google's UserRecoverableAuthIOException or MSAL's reauth path do. Kept for shape parity
     * with the other source libraries' [SyncStatus].
     */
    val consentIntent: Any? = null,
    /**
     * Non-null when the local database itself could not be opened — a condition retrying or
     * background sync cannot recover from. When set,
     * [pl.blizinski.githubissuesstore.GitHubIssuesStoreApi.taskLists]/`tasks` emit empty lists
     * and further reads/writes keep failing the same way until the app is updated or
     * reinstalled. Consumers should show a dedicated diagnostic screen rather than treating this
     * as an ordinary empty state.
     */
    val fatalStorageError: FatalStorageError? = null,
)

data class SyncError(
    val occurredAt: Long,
    val kind: SyncErrorKind,
    val taskLocalId: String? = null,
    val httpStatus: Int? = null,
    val message: String,
)

enum class SyncErrorKind { PUSH_FAILED, PULL_FAILED, AUTH_FAILED, CONSENT_REQUIRED, ADVANCED_PROTECTION }

data class FatalStorageError(
    val occurredAt: Long,
    val summary: String,
    val details: String,
)
