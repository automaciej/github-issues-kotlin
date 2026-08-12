package pl.blizinski.githubissuesstore.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GitHubListRefTest {

    @Test
    fun repoRoundTripsThroughToStringAndParse() {
        val ref = GitHubListRef.Repo(owner = "octocat", name = "Hello-World")
        assertEquals("repo:octocat/Hello-World", ref.toString())
        assertEquals(ref, GitHubListRef.parse(ref.toString()))
    }

    @Test
    fun parseRejectsUnknownPrefix() {
        assertFailsWith<IllegalArgumentException> { GitHubListRef.parse("project:octocat/1") }
    }

    @Test
    fun parseRejectsMalformedRepoRef() {
        assertFailsWith<IllegalArgumentException> { GitHubListRef.parse("repo:no-slash") }
    }
}
