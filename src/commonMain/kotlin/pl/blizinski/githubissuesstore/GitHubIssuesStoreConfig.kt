package pl.blizinski.githubissuesstore

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

data class GitHubIssuesStoreConfig(
    val minPollInterval: Duration = 1.minutes,
    val maxPollInterval: Duration = 30.minutes,
    val dbName: String = "github_issues_store",
    val maxRecentErrors: Int = 50,
)
