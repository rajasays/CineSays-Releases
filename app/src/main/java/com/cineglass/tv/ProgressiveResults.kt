package com.cineglass.tv

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Emit completed providers immediately, retaining configured order in every snapshot. */
internal suspend fun <T> collectProgressively(
    tasks: List<suspend () -> T>,
    onPartial: (List<T>) -> Unit
): List<T> = coroutineScope {
    val channel = Channel<Pair<Int, T>>(Channel.UNLIMITED)
    val completed = sortedMapOf<Int, T>()
    try {
        tasks.forEachIndexed { index, task -> launch { channel.send(index to task()) } }
        repeat(tasks.size) {
            val (index, value) = channel.receive()
            completed[index] = value
            onPartial(completed.values.toList())
        }
        completed.values.toList()
    } finally {
        channel.cancel()
    }
}
