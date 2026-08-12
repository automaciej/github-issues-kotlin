package pl.blizinski.githubissuesstore.models

/** One repository being synced as a task list. [title] is the repo's `owner/name`. */
data class TaskList(
    val id: String,
    val title: String,
)
