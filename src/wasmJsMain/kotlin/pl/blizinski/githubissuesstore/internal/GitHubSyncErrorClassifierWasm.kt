package pl.blizinski.githubissuesstore.internal

import pl.blizinski.githubissuesstore.internal.network.GitHubApiException
import pl.blizinski.tasksync.NoStoredTokenException
import pl.blizinski.tasksync.SyncErrorClassifier
import pl.blizinski.tasksync.SyncErrorKind

/**
 * wasmJs [SyncErrorClassifier] for GitHub Issues — mirrors the Android target's
 * `GitHubSyncErrorClassifier`, just against the wasmJs network source's own [GitHubApiException].
 */
internal class GitHubSyncErrorClassifierWasm : SyncErrorClassifier {

    override fun classifySpecial(e: Exception): SyncErrorKind? = when {
        e is NoStoredTokenException -> SyncErrorKind.AUTH_FAILED
        (e as? GitHubApiException)?.httpStatus == 401 -> SyncErrorKind.AUTH_FAILED
        else -> null
    }

    override fun httpStatus(e: Exception): Int? = (e as? GitHubApiException)?.httpStatus

    /** Always null — a PAT has no interactive consent/re-auth flow, same as Android. */
    override fun extractConsentIntent(e: Exception): Any? = null
}
