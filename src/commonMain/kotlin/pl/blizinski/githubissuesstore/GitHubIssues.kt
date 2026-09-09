package pl.blizinski.githubissuesstore

import pl.blizinski.tasksync.model.RecurrenceStyle
import pl.blizinski.tasksync.model.StoreCapabilities

/**
 * Static facts about the GitHub Issues source, available before any account is connected.
 * [gitHubIssuesStore] (androidMain) / [gitHubIssuesWasmStore] (wasmJsMain) build a
 * [pl.blizinski.tasksync.store.TaskStore] for a connected account.
 */
object GitHubIssues {

    /**
     * A GitHub "list" is a pre-existing repository the app neither creates nor deletes, and a
     * GitHub "delete" closes the issue — hence `supportsListCreation`/`supportsManualDelete`/
     * `supportsNativeMove` are all false. No due date, priority, ordering, subtasks, or
     * recurrence natively.
     */
    val capabilities = StoreCapabilities(
        supportsDueTime = false,
        supportsPriority = false,
        supportsLabels = true,
        supportsManualOrdering = false,
        supportsSubtasks = false,
        supportsMultipleLists = true,
        supportsListCreation = false,
        supportsManualDelete = false,
        supportsNativeMove = false,
        recurrenceStyle = RecurrenceStyle.NONE,
    )
}
