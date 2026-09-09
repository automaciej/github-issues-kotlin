package pl.blizinski.githubissuesstore.internal

import kotlin.test.Test
import kotlin.test.assertEquals

class GitHubIssuesContentMergerTest {

    private val merge = GitHubIssuesContentMerger

    private val base = GitHubTask(
        title = "Base title",
        notes = "base body",
        createdDate = 500L,
        labels = listOf("bug"),
    )

    @Test
    fun disjointFieldEdits_bothSurvive() {
        val local = base.copy(title = "Local title")
        val remote = base.copy(notes = "remote body")

        val merged = merge.merge(base, local, remote, preferLocal = true)

        assertEquals("Local title", merged.title)
        assertEquals("remote body", merged.notes)
    }

    @Test
    fun sameFieldConflict_preferLocalDecides() {
        val local = base.copy(notes = "local body")
        val remote = base.copy(notes = "remote body")

        assertEquals("local body", merge.merge(base, local, remote, preferLocal = true).notes)
        assertEquals("remote body", merge.merge(base, local, remote, preferLocal = false).notes)
    }

    @Test
    fun nullBase_fillsUnsetLocalFieldsFromRemote_contestedUsesPreferLocal() {
        val local = GitHubTask(title = "Local title")            // body never set locally
        val remote = GitHubTask(title = "Server title", notes = "server body", labels = listOf("bug"))

        val merged = merge.merge(null, local, remote, preferLocal = true)
        assertEquals("Local title", merged.title, "contested title -> preferLocal")
        assertEquals("server body", merged.notes, "unset local body filled from server")
        assertEquals(listOf("bug"), merged.labels)

        assertEquals("Server title", merge.merge(null, local, remote, preferLocal = false).title)
    }

    @Test
    fun oneSidedChanges_takeThatSide() {
        assertEquals(
            "remote body",
            merge.merge(base, base.copy(), base.copy(notes = "remote body"), preferLocal = true).notes,
        )
        assertEquals(
            "local title",
            merge.merge(base, base.copy(title = "local title"), base.copy(), preferLocal = false).title,
        )
    }

    @Test
    fun labels_takenFromRemote_notInAppEditor() {
        val local = base.copy(title = "Local title", labels = listOf("stale"))
        val remote = base.copy(labels = listOf("bug", "P1"))

        val merged = merge.merge(base, local, remote, preferLocal = true)

        assertEquals(listOf("bug", "P1"), merged.labels)
        assertEquals("Local title", merged.title)
    }
}
