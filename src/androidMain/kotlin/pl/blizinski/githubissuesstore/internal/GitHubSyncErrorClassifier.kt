package pl.blizinski.githubissuesstore.internal

import pl.blizinski.githubissuesstore.internal.network.GitHubApiException
import pl.blizinski.tasksync.SyncErrorClassifier
import pl.blizinski.tasksync.SyncErrorKind

internal class GitHubSyncErrorClassifier : SyncErrorClassifier {

    override fun classifySpecial(e: Exception): SyncErrorKind? = when {
        (e as? GitHubApiException)?.httpStatus == 401 -> SyncErrorKind.AUTH_FAILED
        else -> null
    }

    override fun httpStatus(e: Exception): Int? = (e as? GitHubApiException)?.httpStatus

    /**
     * Always null: a PAT has no interactive consent/re-auth flow to hand back an intent for —
     * unlike Google's UserRecoverableAuthIOException, recovery here is "generate a new token and
     * reconnect the account" (an explicit user action), not a stored consent intent.
     */
    override fun extractConsentIntent(e: Exception): Any? = null
}
