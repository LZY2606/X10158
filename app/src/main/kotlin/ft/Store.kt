package ft

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * File-backed persistence. A single read-write lock guards mutations; writes
 * are atomic (temp file + move). Historical eval records embed the full trace
 * and context snapshot, so later rule edits never change them.
 */
class Store(private val path: Path) {
    private val lock = ReentrantReadWriteLock()
    private var project: Project

    init {
        project = if (Files.exists(path)) {
            val raw = Files.readString(path, StandardCharsets.UTF_8)
            Codecs.decodeProject(Json.parse(raw))
        } else {
            Project(
                id = "p_" + shortId(),
                name = "默认项目",
                domainSecret = newSecret(),
                flags = emptyMap(),
                contexts = emptyMap(),
                records = emptyList(),
            ).also { persist(it) }
        }
    }

    fun <T> read(fn: (Project) -> T): T = lock.read { fn(project) }
    fun update(fn: (Project) -> Project): Project {
        lock.write {
            val updated = fn(project)
            persist(updated)
            project = updated
        }
        return project
    }

    /** mutate but returns an arbitrary result computed inside the transaction. */
    fun <T> mutateWith(fn: (Project) -> Pair<Project, T>): T {
        var result: T? = null
        lock.write {
            val (updated, res) = fn(project)
            persist(updated)
            project = updated
            result = res
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun persist(p: Project) {
        Files.createDirectories(path.toAbsolutePath().parent)
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.writeString(tmp, Json.write(Codecs.encodeProject(p)), StandardCharsets.UTF_8)
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        private val RNG = SecureRandom()
        fun newSecret(): String {
            val bytes = ByteArray(32)
            RNG.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
        fun shortId(): String = UUID.randomUUID().toString().replace("-", "").substring(0, 12)
        fun now(): String = Instant.now().toString()
    }
}
