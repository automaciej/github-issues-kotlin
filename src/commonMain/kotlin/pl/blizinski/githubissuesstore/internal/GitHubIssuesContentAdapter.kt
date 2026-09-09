package pl.blizinski.githubissuesstore.internal

import pl.blizinski.tasksync.SyncedListRecord
import pl.blizinski.tasksync.SyncedRecord
import pl.blizinski.tasksync.model.Task
import pl.blizinski.tasksync.model.TaskDraft
import pl.blizinski.tasksync.model.TaskList
import pl.blizinski.tasksync.model.TaskRef
import pl.blizinski.tasksync.store.ContentAdapter

/**
 * Maps between GitHub's opaque content types and the shared [Task]/[TaskList]. GitHub Issues has
 * no due date, priority, or recurrence, so a mapped [Task] leaves those at their defaults —
 * never fabricated. Repos are not created or renamed by the app, so the list-content methods
 * are unreachable ([GitHubIssues.capabilities] has `supportsListCreation = false`).
 */
internal object GitHubIssuesContentAdapter : ContentAdapter<GitHubTask, GitHubRepo> {

    override fun toTask(record: SyncedRecord<GitHubTask>) = Task(
        id = TaskRef(localId = record.localId, remoteId = record.remoteId),
        listId = record.listLocalId,
        title = record.content.title,
        notes = record.content.notes,
        isCompleted = record.isCompleted,
        createdDate = record.content.createdDate,
        completedDate = record.content.completedDate,
        labels = record.content.labels,
    )

    override fun toTaskList(list: SyncedListRecord<GitHubRepo>) = TaskList(
        id = list.localId,
        title = "${list.content.owner}/${list.content.name}",
    )

    override fun newContent(draft: TaskDraft, now: Long) =
        GitHubTask(title = draft.title, notes = draft.notes, createdDate = now)

    override fun applyDraft(existing: GitHubTask, draft: TaskDraft) =
        existing.copy(title = draft.title, notes = draft.notes)

    override fun applyCompletion(existing: GitHubTask, completed: Boolean, at: Long?) =
        existing.copy(completedDate = at)

    override fun newListContent(title: String): GitHubRepo =
        error("GitHub repositories are not created by the app")

    override fun applyListTitle(existing: GitHubRepo, title: String): GitHubRepo =
        error("GitHub repositories are not renamed by the app")
}
