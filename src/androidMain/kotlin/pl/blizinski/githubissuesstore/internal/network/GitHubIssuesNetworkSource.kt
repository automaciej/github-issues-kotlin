package pl.blizinski.githubissuesstore.internal.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import pl.blizinski.tasksync.model.AccessTokenProvider
import pl.blizinski.githubissuesstore.internal.GitHubListRef
import pl.blizinski.githubissuesstore.internal.GitHubRepo
import pl.blizinski.githubissuesstore.internal.GitHubTask
import pl.blizinski.tasksync.NetworkSource
import pl.blizinski.tasksync.RemoteListRecord
import pl.blizinski.tasksync.RemoteRecord
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private const val GITHUB_API_BASE = "https://api.github.com"
private const val API_VERSION = "2022-11-28"
private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/**
 * The only place in this library that knows GitHub's own REST/JSON shape and date format —
 * [pl.blizinski.tasksync.SyncEngine]/[pl.blizinski.tasksync.PendingOpsProcessor] never see either.
 *
 * **[getLists] enumerates every repo the token can see** (`GET /user/repos`), the same way
 * `pl.blizinski.microsofttodostore.internal.network.MicrosoftGraphNetworkSource`'s `GET
 * /me/todo/lists` does — every repo the account can access syncs automatically; which of those
 * lists are actually *used* is governed by Task Compass's existing enable/disable-list and
 * workspace-membership UI, the same mechanism Google/Microsoft lists already use. This was a
 * course correction during implementation: an earlier draft had [getLists] refresh only a
 * locally-tracked "selected repos" subset with a bespoke in-app picker for choosing them — wrong
 * layer (see the design doc's Course Correction log entry). A fine-grained PAT scoped to "only
 * select repositories" at creation time on github.com is the intended way to bound a noisy repo
 * list, not an in-app mechanism.
 *
 * [getRecords] uses GitHub's `since`/`state=all` query parameters for real incremental pulls
 * (unlike Microsoft's known tombstone gap) and filters out pull requests (GitHub's Issues
 * endpoints return both, distinguishable by a non-null `pull_request` key).
 */
internal class GitHubIssuesNetworkSource(
    private val tokenProvider: AccessTokenProvider,
    private val httpClient: OkHttpClient = OkHttpClient(),
) : NetworkSource<GitHubTask, GitHubRepo> {

    private val json = Json { ignoreUnknownKeys = true }

    private data class HttpResult(val body: String, val linkHeader: String?)

    private suspend fun request(method: String, url: String, body: String? = null): HttpResult =
        withContext(Dispatchers.IO) {
            val token = tokenProvider.getToken()
            val requestBuilder = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", API_VERSION)
            when (method) {
                "GET" -> requestBuilder.get()
                "POST" -> requestBuilder.post((body ?: "{}").toRequestBody(JSON_MEDIA_TYPE))
                "PATCH" -> requestBuilder.patch((body ?: "{}").toRequestBody(JSON_MEDIA_TYPE))
                else -> error("Unsupported method $method")
            }
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw GitHubApiException(response.code, "GitHub API $method $url failed: ${response.code} $responseBody")
                }
                HttpResult(body = responseBody, linkHeader = response.header("Link"))
            }
        }

    // -----------------------------------------------------------------------
    // Lists — see this class's top doc comment for why getLists() is shaped this way.
    // -----------------------------------------------------------------------

    override suspend fun getLists(): List<RemoteListRecord<GitHubRepo>> {
        val result = mutableListOf<RemoteListRecord<GitHubRepo>>()
        var url: String? = "$GITHUB_API_BASE/user/repos?affiliation=owner,collaborator,organization_member&per_page=100"
        while (url != null) {
            val response = request("GET", url)
            val repos = json.decodeFromString(ListSerializer(GitHubRepoDto.serializer()), response.body)
            result += repos.map { RemoteListRecord(remoteId = GitHubListRef.Repo(it.owner.login, it.name).toString(), content = it.toGitHubRepo()) }
            url = parseNextLink(response.linkHeader)
        }
        return result
    }

    /** Never called in practice — `GitHubIssuesSource`'s `createList` throws before a
     *  `CREATE_LIST` pending op is ever enqueued (see the design doc's Key Design Decisions). */
    override suspend fun createList(content: GitHubRepo): RemoteListRecord<GitHubRepo> =
        throw UnsupportedOperationException("Task Compass does not create GitHub repositories.")

    /** Never called in practice, same reasoning as [createList] — repo renaming isn't exposed
     *  as a Task Compass action for this source. */
    override suspend fun updateList(remoteListId: String, content: GitHubRepo) {}

    /** Never called in practice, same reasoning as [createList] — Task Compass never deletes a
     *  GitHub repository; a repo simply keeps appearing in [getLists] for as long as the token
     *  can see it. Disabling it from sync is a matter of disabling the list in Task Compass's
     *  own list picker (the same mechanism Google/Microsoft lists use), not deleting anything. */
    override suspend fun deleteList(remoteListId: String): Unit =
        throw UnsupportedOperationException("Task Compass does not delete GitHub repositories.")

    // -----------------------------------------------------------------------
    // Issues
    // -----------------------------------------------------------------------

    override suspend fun getRecords(remoteListId: String, updatedMin: Long?): List<RemoteRecord<GitHubTask>> {
        val ref = GitHubListRef.parse(remoteListId) as GitHubListRef.Repo
        val result = mutableListOf<RemoteRecord<GitHubTask>>()
        val sinceParam = updatedMin?.let { "&since=${it.toIso8601Utc().urlEncodeQueryValue()}" }.orEmpty()
        var url: String? = "$GITHUB_API_BASE/repos/${ref.owner}/${ref.name}/issues?state=all&per_page=100$sinceParam"
        while (url != null) {
            val response = request("GET", url)
            val issues = json.decodeFromString(ListSerializer(GitHubIssueDto.serializer()), response.body)
            result += issues.excludingPullRequests().map { it.toRemoteRecord() }
            url = parseNextLink(response.linkHeader)
        }
        return result
    }

    override suspend fun createRecord(remoteListId: String, content: GitHubTask): RemoteRecord<GitHubTask> {
        val ref = GitHubListRef.parse(remoteListId) as GitHubListRef.Repo
        val body = json.encodeToString(GitHubIssueCreateRequest.serializer(), GitHubIssueCreateRequest(title = content.title, body = content.notes, labels = content.labels))
        val dto = json.decodeFromString(GitHubIssueDto.serializer(), request("POST", "$GITHUB_API_BASE/repos/${ref.owner}/${ref.name}/issues", body).body)
        return dto.toRemoteRecord()
    }

    override suspend fun updateRecord(remoteListId: String, remoteId: String, content: GitHubTask) {
        val ref = GitHubListRef.parse(remoteListId) as GitHubListRef.Repo
        val body = json.encodeToString(GitHubIssueUpdateRequest.serializer(), GitHubIssueUpdateRequest(title = content.title, body = content.notes, labels = content.labels))
        request("PATCH", "$GITHUB_API_BASE/repos/${ref.owner}/${ref.name}/issues/$remoteId", body)
    }

    override suspend fun completeRecord(remoteListId: String, remoteId: String) {
        val ref = GitHubListRef.parse(remoteListId) as GitHubListRef.Repo
        val body = json.encodeToString(GitHubIssueStateRequest.serializer(), GitHubIssueStateRequest(state = "closed", stateReason = "completed"))
        request("PATCH", "$GITHUB_API_BASE/repos/${ref.owner}/${ref.name}/issues/$remoteId", body)
    }

    override suspend fun uncompleteRecord(remoteListId: String, remoteId: String) {
        val ref = GitHubListRef.parse(remoteListId) as GitHubListRef.Repo
        val body = json.encodeToString(GitHubIssueStateRequest.serializer(), GitHubIssueStateRequest(state = "open", stateReason = "reopened"))
        request("PATCH", "$GITHUB_API_BASE/repos/${ref.owner}/${ref.name}/issues/$remoteId", body)
    }

    /**
     * No delete endpoint exists on GitHub's REST API. Maps to the closest honest equivalent:
     * closing with `state_reason: not_planned`, distinct from [completeRecord]'s `completed` so
     * a GitHub user browsing the repo can tell "actually done" from "abandoned via Task Compass".
     * See the design doc's Key Design Decisions — a documented, deliberate approximation.
     */
    override suspend fun deleteRecord(remoteListId: String, remoteId: String) {
        val ref = GitHubListRef.parse(remoteListId) as GitHubListRef.Repo
        val body = json.encodeToString(GitHubIssueStateRequest.serializer(), GitHubIssueStateRequest(state = "closed", stateReason = "not_planned"))
        request("PATCH", "$GITHUB_API_BASE/repos/${ref.owner}/${ref.name}/issues/$remoteId", body)
    }

    // moveRecord: not overridden — inherits NetworkSource's default (UnsupportedOperationException),
    // matching "no native cross-repo move" from the design doc's capability table.
}

/** Thrown for any non-2xx GitHub API response; [httpStatus] drives [pl.blizinski.tasksync.SyncErrorClassifier]. */
internal class GitHubApiException(val httpStatus: Int, message: String) : Exception(message)

// ---------------------------------------------------------------------------
// Mapping + date/pagination helpers. This is the only place in the library that touches
// GitHub's own JSON shape and ISO-8601 date format — everywhere above deals in epoch
// milliseconds.
// ---------------------------------------------------------------------------

/** GitHub's Issues endpoints return pull requests too (identifiable via a non-null
 *  `pull_request` key) — filtered out before any issue list ever reaches a [RemoteRecord]. */
internal fun List<GitHubIssueDto>.excludingPullRequests(): List<GitHubIssueDto> =
    filter { it.pullRequest == null }

internal fun GitHubIssueDto.toRemoteRecord(): RemoteRecord<GitHubTask> = RemoteRecord(
    remoteId = number.toString(),
    isCompleted = state == "closed",
    isDeleted = false, // GitHub issues can't be deleted via the REST API — see deleteRecord's doc comment
    remoteUpdatedAt = updatedAt?.parseIso8601ToEpochMs(),
    content = GitHubTask(
        title = title,
        notes = body?.takeIf { it.isNotEmpty() },
        createdDate = createdAt?.parseIso8601ToEpochMs(),
        completedDate = if (state == "closed") closedAt?.parseIso8601ToEpochMs() else null,
        labels = labels.map { it.name },
    ),
)

internal fun GitHubRepoDto.toGitHubRepo(): GitHubRepo = GitHubRepo(
    owner = owner.login,
    name = name,
    description = description,
    isPrivate = private,
)

/** GitHub's own dates: plain ISO-8601 UTC, trailing 'Z', no fractional seconds. */
internal fun String.parseIso8601ToEpochMs(): Long? = try {
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    sdf.timeZone = TimeZone.getTimeZone("UTC")
    sdf.parse(this)?.time
} catch (e: Exception) { null }

internal fun Long.toIso8601Utc(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    sdf.timeZone = TimeZone.getTimeZone("UTC")
    return sdf.format(java.util.Date(this))
}

private fun String.urlEncodeQueryValue(): String =
    java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")

/** Parses a `rel="next"` URL out of a GitHub `Link` response header
 *  (`<url>; rel="next", <url>; rel="last"`), or null if there is no next page. */
internal fun parseNextLink(linkHeader: String?): String? {
    if (linkHeader == null) return null
    for (part in linkHeader.split(",")) {
        val segments = part.split(";").map { it.trim() }
        val url = segments.firstOrNull()?.removePrefix("<")?.removeSuffix(">") ?: continue
        if (segments.drop(1).any { it == "rel=\"next\"" }) return url
    }
    return null
}
