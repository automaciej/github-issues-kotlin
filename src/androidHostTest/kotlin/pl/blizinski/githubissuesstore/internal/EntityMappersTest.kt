package pl.blizinski.githubissuesstore.internal

import pl.blizinski.tasksync.SyncedListRecord
import pl.blizinski.tasksync.SyncedRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class EntityMappersTest {

    @Test
    fun toTaskMapsEnvelopeAndContentFields() {
        val record = SyncedRecord(
            localId = "local-1",
            remoteId = "remote-1",
            listLocalId = "list-1",
            content = GitHubTask(title = "Fix crash", notes = "Repro steps...", labels = listOf("bug")),
            isCompleted = true,
        )

        val task = record.toTask()

        assertEquals("local-1", task.id.localId)
        assertEquals("remote-1", task.id.remoteId)
        assertEquals("list-1", task.listId)
        assertEquals("Fix crash", task.title)
        assertEquals("Repro steps...", task.notes)
        assertEquals(true, task.isCompleted)
        assertEquals(listOf("bug"), task.labels)
    }

    @Test
    fun toTaskNeverFabricatesDueDateOrPriority() {
        // GitHub Issues has neither natively — a mapped Task must never assert otherwise,
        // regardless of what's in the (nonexistent) fields on GitHubTask. See the design doc's
        // "unsupported fields are left at default, never fabricated" testing rule.
        val record = SyncedRecord(localId = "l", remoteId = null, listLocalId = "list", content = GitHubTask(title = "x"))
        val task = record.toTask()
        assertNull(task.dueDate)
        assertFalse(task.dueHasTime)
        assertNull(task.priority)
    }

    @Test
    fun toTaskListCombinesOwnerAndName() {
        val record = SyncedListRecord(localId = "local-1", remoteId = "repo:octocat/Hello-World", content = GitHubRepo(owner = "octocat", name = "Hello-World"))
        val list = record.toTaskList()
        assertEquals("local-1", list.id)
        assertEquals("octocat/Hello-World", list.title)
    }
}
