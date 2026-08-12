package pl.blizinski.githubissuesstore.internal.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.encodeURLQueryComponent
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import pl.blizinski.githubissuesstore.GitHubAccessTokenProvider
import pl.blizinski.githubissuesstore.internal.GitHubListRef
import pl.blizinski.githubissuesstore.internal.GitHubRepo
import pl.blizinski.githubissuesstore.internal.GitHubTask
import pl.blizinski.tasksync.NetworkSource
import pl.blizinski.tasksync.RemoteListRecord
import pl.blizinski.tasksync.RemoteRecord

private const val GITHUB_API_BASE = "https://api.github.com"
private const val API_VERSION = "2022-11-28"

/**
 * wasmJs [NetworkSource] for GitHub Issues — the Ktor-based counterpart of the Android target's
 * `GitHubIssuesNetworkSource` (OkHttp-based, JVM/Android-only). Shares the same wire DTOs
 * (`GitHubApiModels.kt`) and [GitHubListRef]/[GitHubTask]/[GitHubRepo] with the Android
 * implementation — only the HTTP client and date parsing differ. See TaskCompass's
 * Docs/designs/2026-07-30-web-wasmjs-google-tasks-poc.md (Stage G).
 */
@OptIn(ExperimentalTime::class)
internal class GitHubIssuesNetworkSourceWasm(
    private val tokenProvider: GitHubAccessTokenProvider,
    private val httpClient: HttpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    },
) : NetworkSource<GitHubTask, GitHubRepo> {

    private suspend inline fun <reified T> getPage(url: String): Pair<T, String?> {
        val token = tokenProvider.getToken()
        val response: HttpResponse = httpClient.get(url) {
            header("Authorization", "Bearer $token")
            header("Accept", "application/vnd.github+json")
            header("X-GitHub-Api-Version", API_VERSION)
        }
        if (!response.status.isSuccess()) {
            throw GitHubApiException(response.status.value, "GitHub API GET $url failed: ${response.status}")
        }
        return response.body<T>() to response.headers["Link"]
    }

    private suspend fun mutate(method: String, url: String, body: Any? = null): HttpResponse {
        val token = tokenProvider.getToken()
        val response: HttpResponse = when (method) {
            "POST" -> httpClient.post(url) { header("Authorization", "Bearer $token"); header("Accept", "application/vnd.github+json"); header("X-GitHub-Api-Version", API_VERSION); if (body != null) setBody(body) }
            "PATCH" -> httpClient.patch(url) { header("Authorization", "Bearer $token"); header("Accept", "application/vnd.github+json"); header("X-GitHub-Api-Version", API_VERSION); if (body != null) setBody(body) }
            "DELETE" -> httpClient.delete(url) { header("Authorization", "Bearer $token"); header("Accept", "application/vnd.github+json"); header("X-GitHub-Api-Version", API_VERSION) }
            else -> error("Unsupported method $method")
        }
        if (!response.status.isSuccess()) {
            throw GitHubApiException(response.status.value, "GitHub API $method $url failed: ${response.status}")
        }
        return response
    }

    // -----------------------------------------------------------------------
    // Lists — every repo the connected token can see syncs automatically, same as Android.
    // -----------------------------------------------------------------------

    override suspend fun getLists(): List<RemoteListRecord<GitHubRepo>> {
        val result = mutableListOf<RemoteListRecord<GitHubRepo>>()
        var url: String? = "$GITHUB_API_BASE/user/repos?affiliation=owner,collaborator,organization_member&per_page=100"
        while (url != null) {
            val (repos, linkHeader) = getPage<List<GitHubRepoDto>>(url)
            result += repos.map { RemoteListRecord(remoteId = GitHubListRef.Repo(it.owner.login, it.name).toString(), content = it.toGitHubRepo()) }
            url = parseNextLink(linkHeader)
        }
        return result
    }

    override suspend fun createList(content: GitHubRepo): RemoteListRecord<GitHubRepo> =
        throw UnsupportedOperationException("Task Compass does not create GitHub repositories.")

    override suspend fun updateList(remoteListId: String, content: GitHubRepo) {}

    override suspend fun deleteList(remoteListId: String): Unit =
        throw UnsupportedOperationException("Task Compass does not delete GitHub repositories.")

    // -----------------------------------------------------------------------
    // Issues
    // -----------------------------------------------------------------------

    override suspend fun getRecords(remoteListId: String, updatedMin: Long?): List<RemoteRecord<GitHubTask>> {
        val ref = GitHubListRef.parse(remoteListId) as GitHubListRef.Repo
        val result = mutableListOf<RemoteRecord<GitHubTask>>()
        val sinceParam = updatedMin?.let { "&since=${it.toIso8601Utc().encodeURLQueryComponent()}" }.orEmpty()
        var url: String? = "$GITHUB_API_BASE/repos/${ref.owner}/${ref.name}/issues?state=all&per_page=100$sinceParam"
        while (url != null) {
            val (issues, linkHeader) = getPage<List<GitHubIssueDto>>(url)
            result += issues.filter { it.pullRequest == null }.map { it.toRemoteRecord() }
            url = parseNextLink(linkHeader)
        }
        return result
    }

    override suspend fun createRecord(remoteListId: String, content: GitHubTask): RemoteRecord<GitHubTask> {
        val ref = GitHubListRef.parse(remoteListId) as GitHubListRef.Repo
        val response = mutate("POST", "$GITHUB_API_BASE/repos/${ref.owner}/${ref.name}/issues", GitHubIssueCreateRequest(title = content.title, body = content.notes, labels = content.labels))
        return response.body<GitHubIssueDto>().toRemoteRecord()
    }

    override suspend fun updateRecord(remoteListId: String, remoteId: String, content: GitHubTask) {
        val ref = GitHubListRef.parse(remoteListId) as GitHubListRef.Repo
        mutate("PATCH", "$GITHUB_API_BASE/repos/${ref.owner}/${ref.name}/issues/$remoteId", GitHubIssueUpdateRequest(title = content.title, body = content.notes, labels = content.labels))
    }

    override suspend fun completeRecord(remoteListId: String, remoteId: String) {
        val ref = GitHubListRef.parse(remoteListId) as GitHubListRef.Repo
        mutate("PATCH", "$GITHUB_API_BASE/repos/${ref.owner}/${ref.name}/issues/$remoteId", GitHubIssueStateRequest(state = "closed", stateReason = "completed"))
    }

    override suspend fun uncompleteRecord(remoteListId: String, remoteId: String) {
        val ref = GitHubListRef.parse(remoteListId) as GitHubListRef.Repo
        mutate("PATCH", "$GITHUB_API_BASE/repos/${ref.owner}/${ref.name}/issues/$remoteId", GitHubIssueStateRequest(state = "open", stateReason = "reopened"))
    }

    /** No delete endpoint exists on GitHub's REST API — closes with `state_reason: not_planned`, same as Android. */
    override suspend fun deleteRecord(remoteListId: String, remoteId: String) {
        val ref = GitHubListRef.parse(remoteListId) as GitHubListRef.Repo
        mutate("PATCH", "$GITHUB_API_BASE/repos/${ref.owner}/${ref.name}/issues/$remoteId", GitHubIssueStateRequest(state = "closed", stateReason = "not_planned"))
    }

    // moveRecord: not overridden — inherits NetworkSource's default (UnsupportedOperationException),
    // matching "no native cross-repo move", same as Android.
}

/** Thrown for any non-2xx GitHub API response; [httpStatus] drives [pl.blizinski.tasksync.SyncErrorClassifier]. */
internal class GitHubApiException(val httpStatus: Int, message: String) : Exception(message)

private fun GitHubRepoDto.toGitHubRepo(): GitHubRepo = GitHubRepo(
    owner = owner.login,
    name = name,
    description = description,
    isPrivate = private,
)

@OptIn(ExperimentalTime::class)
private fun GitHubIssueDto.toRemoteRecord(): RemoteRecord<GitHubTask> = RemoteRecord(
    remoteId = number.toString(),
    isCompleted = state == "closed",
    isDeleted = false,
    remoteUpdatedAt = updatedAt?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() },
    content = GitHubTask(
        title = title,
        notes = body?.takeIf { it.isNotEmpty() },
        createdDate = createdAt?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() },
        completedDate = if (state == "closed") closedAt?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() } else null,
        labels = labels.map { it.name },
    ),
)

@OptIn(ExperimentalTime::class)
private fun Long.toIso8601Utc(): String = Instant.fromEpochMilliseconds(this).toString()

/** Parses a `rel="next"` URL out of a GitHub `Link` response header, or null if there is no next page. */
private fun parseNextLink(linkHeader: String?): String? {
    if (linkHeader == null) return null
    for (part in linkHeader.split(",")) {
        val segments = part.split(";").map { it.trim() }
        val url = segments.firstOrNull()?.removePrefix("<")?.removeSuffix(">") ?: continue
        if (segments.drop(1).any { it == "rel=\"next\"" }) return url
    }
    return null
}
