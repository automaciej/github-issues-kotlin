package pl.blizinski.githubissuesstore

import android.content.Context
import kotlinx.serialization.serializer
import pl.blizinski.githubissuesstore.internal.GitHubIssuesContentAdapter
import pl.blizinski.githubissuesstore.internal.GitHubRepo
import pl.blizinski.githubissuesstore.internal.GitHubTask
import pl.blizinski.githubissuesstore.internal.network.GitHubApiException
import pl.blizinski.githubissuesstore.internal.network.GitHubIssuesNetworkSource
import pl.blizinski.tasksync.HttpStatusSyncErrorClassifier
import pl.blizinski.tasksync.model.AccessTokenProvider
import pl.blizinski.tasksync.model.StoreConfig
import pl.blizinski.tasksync.store.TaskStore
import pl.blizinski.tasksync.store.buildAndroidTaskStore

/**
 * Builds a local-first [TaskStore] for GitHub Issues on Android. Reads come from the Room cache;
 * writes are optimistic and synced in the background. Every repository the connected token can
 * see syncs automatically — there is no add/remove-repo concept in this library (that is the
 * consuming app's enabled-list concern). One instance per connected account, keyed by
 * [config]`.dbName`.
 *
 * No legacy on-disk schema, so no Room migrations are passed.
 */
fun gitHubIssuesStore(
    context: Context,
    tokenProvider: AccessTokenProvider,
    config: StoreConfig,
): TaskStore = buildAndroidTaskStore(
    context = context,
    config = config,
    capabilities = GitHubIssues.capabilities,
    network = GitHubIssuesNetworkSource(tokenProvider),
    errorClassifier = HttpStatusSyncErrorClassifier(statusOf = { (it as? GitHubApiException)?.httpStatus }),
    recordSerializer = serializer<GitHubTask>(),
    listSerializer = serializer<GitHubRepo>(),
    adapter = GitHubIssuesContentAdapter,
)
