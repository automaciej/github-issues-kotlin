package pl.blizinski.githubissuesstore

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** The GitHub login a verified token belongs to. */
data class GitHubUser(val login: String)

/** Thrown by [GitHubTokenVerifier.verify] when the token is invalid/expired/revoked
 *  (GitHub returned 401), or any other non-2xx response. [httpStatus] is null only for a
 *  network-level failure (no response at all). */
class GitHubInvalidTokenException(val httpStatus: Int?, message: String) : Exception(message)

/**
 * Verifies a GitHub Personal Access Token via `GET /user` before it's stored anywhere — lets
 * `composeApp`'s PAT-entry dialog surface "invalid token" immediately, rather than accepting a
 * bad token and only discovering it on the next background sync. Kept inside this library (not
 * composeApp) so the OkHttp dependency and GitHub's response shape stay in one place, consistent
 * with [pl.blizinski.githubissuesstore.internal.network.GitHubIssuesNetworkSource] being "the
 * only place that knows GitHub's own REST/JSON shape" — this is a second, narrower instance of
 * that same rule, used before an account (and its own [GitHubIssuesStore]) exists yet.
 */
object GitHubTokenVerifier {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class UserResponse(val login: String = "")

    suspend fun verify(token: String, httpClient: OkHttpClient = OkHttpClient()): GitHubUser =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://api.github.com/user")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .get()
                .build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw GitHubInvalidTokenException(response.code, "GET /user failed: ${response.code} $body")
                    }
                    GitHubUser(login = json.decodeFromString(UserResponse.serializer(), body).login)
                }
            } catch (e: GitHubInvalidTokenException) {
                throw e
            } catch (e: Exception) {
                throw GitHubInvalidTokenException(httpStatus = null, message = e.message ?: "Network error verifying token")
            }
        }
}
