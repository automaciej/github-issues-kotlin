package pl.blizinski.githubissuesstore.internal

import pl.blizinski.githubissuesstore.internal.network.GitHubApiException
import pl.blizinski.tasksync.SyncErrorKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitHubSyncErrorClassifierTest {

    private val classifier = GitHubSyncErrorClassifier()

    @Test
    fun classifySpecialReturnsAuthFailedFor401() {
        assertEquals(SyncErrorKind.AUTH_FAILED, classifier.classifySpecial(GitHubApiException(401, "Unauthorized")))
    }

    @Test
    fun classifySpecialReturnsNullForNon401() {
        assertNull(classifier.classifySpecial(GitHubApiException(500, "Server error")))
    }

    @Test
    fun classifySpecialReturnsNullForUnrelatedException() {
        assertNull(classifier.classifySpecial(RuntimeException("network blip")))
    }

    @Test
    fun httpStatusReadsStatusCodeFromGitHubApiException() {
        assertEquals(404, classifier.httpStatus(GitHubApiException(404, "Not Found")))
    }

    @Test
    fun httpStatusIsNullForUnrelatedException() {
        assertNull(classifier.httpStatus(RuntimeException("network blip")))
    }

    @Test
    fun extractConsentIntentIsAlwaysNull() {
        // By design — a PAT has no interactive consent/re-auth flow to hand back an intent for.
        assertNull(classifier.extractConsentIntent(GitHubApiException(401, "Unauthorized")))
        assertNull(classifier.extractConsentIntent(RuntimeException("network blip")))
    }
}
