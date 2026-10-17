package io.github.alexandrelam.photos.sync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** The set of phone file keys the server has, kept in a small text file so badges work offline. */
internal class SyncedKeys(context: Context) {
    private val file = File(context.filesDir, "synced-keys.txt")
    private val _keys = MutableStateFlow(load())
    val keys: StateFlow<Set<String>> = _keys.asStateFlow()

    @Synchronized
    fun add(key: String) {
        if (key in _keys.value) return
        _keys.value = _keys.value + key
        file.appendText(key + "\n")
    }

    @Synchronized
    fun addAll(keys: Collection<String>) {
        val added = keys.filter { it !in _keys.value }
        if (added.isEmpty()) return
        _keys.value = _keys.value + added
        file.appendText(added.joinToString("\n", postfix = "\n"))
    }

    @Synchronized
    fun removeAll(keys: Collection<String>) {
        val drop = keys.toSet()
        if (_keys.value.none { it in drop }) return
        replace(_keys.value - drop)
    }

    @Synchronized
    fun replace(keys: Set<String>) {
        _keys.value = keys
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(keys.joinToString("\n", postfix = if (keys.isEmpty()) "" else "\n"))
        tmp.renameTo(file)
    }

    private fun load(): Set<String> =
        if (file.exists()) file.readLines().filterTo(HashSet()) { it.isNotBlank() } else emptySet()
}
