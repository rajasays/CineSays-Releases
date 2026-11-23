package com.cineglass.tv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ProgressiveResultsTest {
    @Test fun fastProviderIsVisibleBeforeSlowProviderAndFinalOrderIsStable() = runBlocking {
        withTimeout(2000) {
            val slow = CompletableDeferred<Unit>()
            val first = CompletableDeferred<List<String>>()
            val result = async {
                collectProgressively(listOf<suspend () -> String>(
                    { slow.await(); "slow" }, { "fast" }
                )) { first.complete(it) }
            }
            assertEquals(listOf("fast"), first.await())
            assertFalse(result.isCompleted)
            slow.complete(Unit)
            assertEquals(listOf("slow", "fast"), result.await())
        }
    }

    @Test fun closingSearchCancelsProviderWork() = runBlocking {
        withTimeout(2000) {
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val search = async {
                collectProgressively(listOf<suspend () -> String>({
                    try { started.complete(Unit); awaitCancellation() }
                    finally { stopped.complete(Unit) }
                })) {}
            }
            started.await()
            search.cancelAndJoin()
            assertTrue(stopped.isCompleted)
        }
    }
}
