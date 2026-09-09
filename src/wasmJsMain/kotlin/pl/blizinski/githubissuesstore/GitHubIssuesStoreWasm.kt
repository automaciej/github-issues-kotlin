package pl.blizinski.githubissuesstore

import kotlinx.serialization.serializer
import pl.blizinski.githubissuesstore.internal.GitHubIssuesContentAdapter
import pl.blizinski.githubissuesstore.internal.GitHubRepo
import pl.blizinski.githubissuesstore.internal.GitHubTask
import pl.blizinski.githubissuesstore.internal.network.GitHubApiException
import pl.blizinski.githubissuesstore.internal.network.GitHubIssuesNetworkSourceWasm
import pl.blizinski.tasksync.HttpStatusSyncErrorClassifier
import pl.blizinski.tasksync.NoStoredTokenException
import pl.blizinski.tasksync.SyncErrorKind
import pl.blizinski.tasksync.model.AccessTokenProvider
import pl.blizinski.tasksync.model.StoreConfig
import pl.blizinski.tasksync.store.TaskStore
import pl.blizinski.tasksync.store.buildWasmTaskStore

/**
 * Builds an IndexedDB-backed [TaskStore] for GitHub Issues on wasmJs, syncing on demand only —
 * see TaskCompass's `Docs/designs/2026-07-30-web-wasmjs-google-tasks-poc.md` (Stage G).
 */
fun gitHubIssuesWasmStore(
    tokenProvider: AccessTokenProvider,
    config: StoreConfig,
): TaskStore = buildWasmTaskStore(
    config = config,
    capabilities = GitHubIssues.capabilities,
    network = GitHubIssuesNetworkSourceWasm(tokenProvider),
    errorClassifier = HttpStatusSyncErrorClassifier(
        statusOf = { (it as? GitHubApiException)?.httpStatus },
        extraSpecial = { if (it is NoStoredTokenException) SyncErrorKind.AUTH_FAILED else null },
    ),
    recordSerializer = serializer<GitHubTask>(),
    listSerializer = serializer<GitHubRepo>(),
    adapter = GitHubIssuesContentAdapter,
)
