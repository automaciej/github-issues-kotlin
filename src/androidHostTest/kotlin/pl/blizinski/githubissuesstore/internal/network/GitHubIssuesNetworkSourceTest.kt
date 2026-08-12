package pl.blizinski.githubissuesstore.internal.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubIssuesNetworkSourceTest {

    // -----------------------------------------------------------------------
    // ISO-8601: plain UTC, trailing 'Z', no fractional seconds — GitHub's own format,
    // simpler than Microsoft Graph's dateTimeTimeZone (see GitHubIssuesNetworkSource's doc).
    // -----------------------------------------------------------------------

    @Test
    fun iso8601RoundTrips() {
        // 2026-03-07T13:45:30Z
        val epochMs = 1772891130000L
        assertEquals(epochMs, epochMs.toIso8601Utc().parseIso8601ToEpochMs())
    }

    @Test
    fun iso8601MalformedParsesToNull() {
        assertNull("not-a-date".parseIso8601ToEpochMs())
    }

    // -----------------------------------------------------------------------
    // Pagination — GitHub's Link header, unlike Graph's @odata.nextLink JSON field.
    // -----------------------------------------------------------------------

    @Test
    fun parseNextLinkExtractsNextRelUrl() {
        val header = """<https://api.github.com/repos/o/r/issues?page=2>; rel="next", <https://api.github.com/repos/o/r/issues?page=5>; rel="last""""
        assertEquals("https://api.github.com/repos/o/r/issues?page=2", parseNextLink(header))
    }

    @Test
    fun parseNextLinkReturnsNullWithoutNextRel() {
        val header = """<https://api.github.com/repos/o/r/issues?page=1>; rel="prev""""
        assertNull(parseNextLink(header))
    }

    @Test
    fun parseNextLinkReturnsNullForNullHeader() {
        assertNull(parseNextLink(null))
    }

    // -----------------------------------------------------------------------
    // Pull-request filtering — GitHub's Issues endpoints return PRs too.
    // -----------------------------------------------------------------------

    @Test
    fun excludingPullRequestsDropsEntriesWithAPullRequestKey() {
        val issue = GitHubIssueDto(number = 1, title = "Real issue")
        val pr = GitHubIssueDto(number = 2, title = "Actually a PR", pullRequest = GitHubPullRequestRefDto(url = "https://api.github.com/repos/o/r/pulls/2"))

        assertEquals(listOf(issue), listOf(issue, pr).excludingPullRequests())
    }

    // -----------------------------------------------------------------------
    // GitHubIssueDto <-> RemoteRecord<GitHubTask>
    // -----------------------------------------------------------------------

    @Test
    fun toRemoteRecordMapsClosedStateAndCompletedDate() {
        val closedAt = 1772891130000L
        val dto = GitHubIssueDto(
            number = 1347,
            title = "Found a bug",
            body = "I'm having a problem with this.",
            state = "closed",
            stateReason = "completed",
            labels = listOf(GitHubLabelDto(name = "bug")),
            updatedAt = closedAt.toIso8601Utc(),
            closedAt = closedAt.toIso8601Utc(),
        )

        val record = dto.toRemoteRecord()

        assertEquals("1347", record.remoteId)
        assertTrue(record.isCompleted)
        assertFalse(record.isDeleted) // GitHub issues can't be deleted via the REST API
        assertEquals(closedAt, record.remoteUpdatedAt)
        assertEquals("Found a bug", record.content.title)
        assertEquals("I'm having a problem with this.", record.content.notes)
        assertEquals(closedAt, record.content.completedDate)
        assertEquals(listOf("bug"), record.content.labels)
    }

    @Test
    fun toRemoteRecordLeavesCompletedDateNullWhenStillOpen() {
        val dto = GitHubIssueDto(number = 1, title = "Open issue", state = "open")
        assertNull(dto.toRemoteRecord().content.completedDate)
    }

    @Test
    fun toRemoteRecordTreatsEmptyBodyAsNullNotes() {
        val dto = GitHubIssueDto(number = 1, title = "x", body = "")
        assertNull(dto.toRemoteRecord().content.notes)
    }

    // -----------------------------------------------------------------------
    // GitHubRepoDto <-> GitHubRepo
    // -----------------------------------------------------------------------

    @Test
    fun toGitHubRepoMapsOwnerLoginAndName() {
        val dto = GitHubRepoDto(name = "Hello-World", owner = GitHubOwnerDto(login = "octocat"), description = "My first repo", private = false)
        val repo = dto.toGitHubRepo()
        assertEquals("octocat", repo.owner)
        assertEquals("Hello-World", repo.name)
        assertEquals("My first repo", repo.description)
        assertFalse(repo.isPrivate)
    }
}
