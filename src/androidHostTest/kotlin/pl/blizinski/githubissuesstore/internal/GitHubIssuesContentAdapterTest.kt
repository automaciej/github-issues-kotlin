package pl.blizinski.githubissuesstore.internal

import pl.blizinski.tasksync.SyncedListRecord
import pl.blizinski.tasksync.SyncedRecord
import pl.blizinski.tasksync.model.TaskDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class GitHubIssuesContentAdapterTest {

    private val adapter = GitHubIssuesContentAdapter

    @Test
    fun toTask_mapsEnvelopeAndContentFields() {
        val record = SyncedRecord(
            localId = "local-1",
            remoteId = "remote-1",
            listLocalId = "list-1",
            content = GitHubTask(title = "Fix crash", notes = "Repro steps...", labels = listOf("bug")),
            isCompleted = true,
        )

        val task = adapter.toTask(record)

        assertEquals("local-1", task.id.localId)
        assertEquals("remote-1", task.id.remoteId)
        assertEquals("list-1", task.listId)
        assertEquals("Fix crash", task.title)
        assertEquals("Repro steps...", task.notes)
        assertEquals(true, task.isCompleted)
        assertEquals(listOf("bug"), task.labels)
    }

    @Test
    fun toTask_neverFabricatesDueDateOrPriorityOrRecurrence() {
        val record = SyncedRecord(localId = "l", remoteId = null, listLocalId = "list", content = GitHubTask(title = "x"))
        val task = adapter.toTask(record)
        assertNull(task.dueDate)
        assertFalse(task.dueHasTime)
        assertNull(task.priority)
        assertNull(task.recurrenceRule)
    }

    @Test
    fun toTaskList_combinesOwnerAndName() {
        val record = SyncedListRecord(localId = "local-1", remoteId = "repo:octocat/Hello-World", content = GitHubRepo(owner = "octocat", name = "Hello-World"))
        val list = adapter.toTaskList(record)
        assertEquals("local-1", list.id)
        assertEquals("octocat/Hello-World", list.title)
    }

    @Test
    fun newContent_takesTitleNotesAndCreatedDate_only() {
        val content = adapter.newContent(
            TaskDraft(title = "New issue", notes = "body", dueDate = 999L, recurrenceRule = null),
            now = 123L,
        )
        assertEquals("New issue", content.title)
        assertEquals("body", content.notes)
        assertEquals(123L, content.createdDate)
    }

    @Test
    fun applyDraft_updatesTitleAndNotes_keepsLabels() {
        val existing = GitHubTask(title = "old", notes = "old body", createdDate = 1L, labels = listOf("bug"))
        val updated = adapter.applyDraft(existing, TaskDraft(title = "new", notes = "new body"))
        assertEquals("new", updated.title)
        assertEquals("new body", updated.notes)
        assertEquals(listOf("bug"), updated.labels)
        assertEquals(1L, updated.createdDate)
    }

    @Test
    fun applyCompletion_setsAndClearsCompletedDate() {
        val existing = GitHubTask(title = "x")
        assertEquals(50L, adapter.applyCompletion(existing, completed = true, at = 50L).completedDate)
        assertNull(adapter.applyCompletion(existing.copy(completedDate = 50L), completed = false, at = null).completedDate)
    }
}
