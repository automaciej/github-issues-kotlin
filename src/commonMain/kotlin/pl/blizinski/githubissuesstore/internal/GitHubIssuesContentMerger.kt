package pl.blizinski.githubissuesstore.internal

import pl.blizinski.tasksync.store.ContentMerger
import pl.blizinski.tasksync.store.contentMerger

/**
 * Three-way merge for [GitHubTask] content, passed to `buildAndroidTaskStore` /
 * `buildWasmTaskStore` so a title edited on one device and a body edited on another both survive
 * instead of one overwriting the other.
 *
 * Only the fields this app can edit locally are picked: [GitHubTask.title] and [GitHubTask.notes]
 * (via `applyDraft`), plus [GitHubTask.completedDate] (via `applyCompletion`). The result starts
 * from the just-pulled `remote`, so `labels` and `createdDate` are carried through unchanged.
 */
internal val GitHubIssuesContentMerger: ContentMerger<GitHubTask> =
    contentMerger(emptyBase = GitHubTask(title = "")) {
        remote.copy(
            title = pick { it.title },
            notes = pick { it.notes },
            completedDate = pick { it.completedDate },
        )
    }
