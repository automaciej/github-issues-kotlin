package pl.blizinski.githubissuesstore.models

data class Task(
    val id: TaskId,
    val listId: String,
    val title: String,
    val notes: String? = null,
    val isCompleted: Boolean = false,
    val createdDate: Long? = null,
    /** GitHub Issues has no native due date — always null. Kept for shape parity with the
     *  other source libraries' [Task] models; see the design doc's capability table. */
    val dueDate: Long? = null,
    val dueHasTime: Boolean = false,
    val completedDate: Long? = null,
    /** GitHub Issues has no native priority concept — always null. */
    val priority: Int? = null,
    /** GitHub's native, first-class issue labels. */
    val labels: List<String> = emptyList(),
)
