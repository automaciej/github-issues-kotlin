package pl.blizinski.githubissuesstore.internal.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the GitHub REST API's `issue`/repository resources — the only place in this
 * library that knows GitHub's own JSON shape. [pl.blizinski.tasksync.SyncEngine]/
 * [pl.blizinski.tasksync.PendingOpsProcessor] never see these.
 */
@Serializable
internal data class GitHubIssueDto(
    val id: Long = 0,
    val number: Long = 0,
    val title: String = "",
    val body: String? = null,
    /** "open" | "closed". */
    val state: String = "open",
    @SerialName("state_reason") val stateReason: String? = null,
    val labels: List<GitHubLabelDto> = emptyList(),
    /** Non-null for a pull request masquerading as an issue — see the design doc's "Issues
     *  endpoints also return pull requests" note. Filtered out before ever reaching a
     *  [pl.blizinski.tasksync.RemoteRecord]. */
    @SerialName("pull_request") val pullRequest: GitHubPullRequestRefDto? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("closed_at") val closedAt: String? = null,
)

@Serializable
internal data class GitHubLabelDto(
    val name: String = "",
)

@Serializable
internal data class GitHubPullRequestRefDto(
    val url: String? = null,
)

@Serializable
internal data class GitHubIssueCreateRequest(
    val title: String,
    val body: String? = null,
    val labels: List<String> = emptyList(),
)

@Serializable
internal data class GitHubIssueUpdateRequest(
    val title: String,
    val body: String? = null,
    val labels: List<String> = emptyList(),
)

@Serializable
internal data class GitHubIssueStateRequest(
    val state: String,
    @SerialName("state_reason") val stateReason: String? = null,
)

@Serializable
internal data class GitHubRepoDto(
    val name: String = "",
    val owner: GitHubOwnerDto = GitHubOwnerDto(),
    val description: String? = null,
    val private: Boolean = false,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
internal data class GitHubOwnerDto(
    val login: String = "",
)
