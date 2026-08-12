package pl.blizinski.githubissuesstore

/**
 * Supplies a GitHub Personal Access Token. Implemented by TaskCompass itself (wrapping wherever
 * the PAT is stored — `EncryptedSharedPreferences`, per the design doc) so this library never
 * hardcodes a storage mechanism.
 *
 * Unlike [pl.blizinski.tasksync.SyncErrorClassifier]-classified auth failures for Google/
 * Microsoft, a PAT has no silent-refresh or interactive-reauth concept — it's either valid or
 * it isn't. [getToken] is expected to simply return the stored token; a 401 response from
 * GitHub's API is what signals the token needs to be replaced (see [GitHubSyncErrorClassifier]),
 * not an exception thrown from here.
 */
interface GitHubAccessTokenProvider {
    suspend fun getToken(): String
}
