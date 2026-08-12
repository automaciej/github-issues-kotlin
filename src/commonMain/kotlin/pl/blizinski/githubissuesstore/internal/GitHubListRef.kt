package pl.blizinski.githubissuesstore.internal

/**
 * What a GitHub-backed "list" actually is, encoded into [pl.blizinski.tasksync.NetworkSource]'s
 * plain-`String` `remoteListId`. Only [Repo] is implemented — a future `Project` case (GitHub
 * Projects v2, via GraphQL) is a planned follow-on (see the design doc's Stage D4), reserved for
 * here so adding it later doesn't force a migration of every already-synced repo-backed list.
 */
internal sealed interface GitHubListRef {
    data class Repo(val owner: String, val name: String) : GitHubListRef {
        override fun toString() = "repo:$owner/$name"
    }

    companion object {
        fun parse(remoteListId: String): GitHubListRef = when {
            remoteListId.startsWith("repo:") -> {
                val parts = remoteListId.removePrefix("repo:").split("/", limit = 2)
                require(parts.size == 2) { "Malformed GitHub repo list ref: $remoteListId" }
                Repo(owner = parts[0], name = parts[1])
            }
            else -> throw IllegalArgumentException("Unrecognized GitHub list ref: $remoteListId")
        }
    }
}
