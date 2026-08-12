package pl.blizinski.githubissuesstore.internal

import pl.blizinski.githubissuesstore.models.Task
import pl.blizinski.githubissuesstore.models.TaskId
import pl.blizinski.githubissuesstore.models.TaskList
import pl.blizinski.tasksync.SyncedListRecord
import pl.blizinski.tasksync.SyncedRecord

internal fun SyncedRecord<GitHubTask>.toTask(): Task = Task(
    id = TaskId(localId = localId, remoteId = remoteId),
    listId = listLocalId,
    title = content.title,
    notes = content.notes,
    isCompleted = isCompleted,
    createdDate = content.createdDate,
    // dueDate/dueHasTime/priority stay at Task's own defaults (null/false/null) — GitHub Issues
    // has neither natively, so there is structurally nothing to map here (see GitHubTask's doc).
    completedDate = content.completedDate,
    labels = content.labels,
)

internal fun SyncedListRecord<GitHubRepo>.toTaskList(): TaskList = TaskList(
    id = localId,
    title = "${content.owner}/${content.name}",
)
