package app.stmobile.models

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Two-way typed synchronization for SillyTavern's `config.yaml`.
 * Backed by an internal [LinkedHashMap] preserving key order and any unknown/custom
 * configuration keys across read/write cycles.
 *
 * Note: Serializing via SnakeYAML strips inline YAML comments by design.
 */
class StConfig(
    private val rawMap: LinkedHashMap<String, Any?>,
    var sourceFile: File? = null,
) {

    var port: Int
        get() = (rawMap["port"] as? Number)?.toInt()
            ?: (rawMap["port"] as? String)?.toIntOrNull()
            ?: DEFAULT_PORT
        set(value) {
            rawMap["port"] = value
        }

    var listen: Boolean
        get() = when (val v = rawMap["listen"]) {
            is Boolean -> v
            is String -> v.equals("true", ignoreCase = true)
            else -> false
        }
        set(value) {
            rawMap["listen"] = value
        }

    var whitelistMode: Boolean
        get() = when (val v = rawMap["whitelistMode"]) {
            is Boolean -> v
            is String -> v.equals("true", ignoreCase = true)
            else -> true
        }
        set(value) {
            rawMap["whitelistMode"] = value
        }

    @Suppress("UNCHECKED_CAST")
    var whitelist: List<String>
        get() = (rawMap["whitelist"] as? List<*>)?.mapNotNull { it?.toString() }
            ?: listOf("127.0.0.1", "::1")
        set(value) {
            rawMap["whitelist"] = ArrayList(value)
        }

    var enableServerPlugins: Boolean
        get() = when (val v = rawMap["enableServerPlugins"]) {
            is Boolean -> v
            is String -> v.equals("true", ignoreCase = true)
            else -> false
        }
        set(value) {
            rawMap["enableServerPlugins"] = value
        }

    var skipContentCheck: Boolean
        get() = when (val v = rawMap["skipContentCheck"]) {
            is Boolean -> v
            is String -> v.equals("true", ignoreCase = true)
            else -> false
        }
        set(value) {
            rawMap["skipContentCheck"] = value
        }

    /** Direct access to the underlying preserved map. */
    fun raw(): LinkedHashMap<String, Any?> = rawMap

    fun get(key: String): Any? = rawMap[key]

    fun set(key: String, value: Any?) {
        rawMap[key] = value
    }

    /** Resolves nested keys via dotted notation, e.g. "protocol.ipv4". */
    fun getPath(path: String): Any? {
        val parts = path.split('.')
        var current: Any? = rawMap
        for (part in parts) {
            if (current !is Map<*, *>) return null
            current = current[part]
        }
        return current
    }

    /** Sets nested keys via dotted notation, e.g. "protocol.ipv4". */
    @Suppress("UNCHECKED_CAST")
    fun setPath(path: String, value: Any?) {
        val parts = path.split('.')
        if (parts.isEmpty()) return
        if (parts.size == 1) {
            rawMap[parts[0]] = value
            return
        }

        var current: MutableMap<String, Any?> = rawMap
        for (i in 0 until parts.size - 1) {
            val part = parts[i]
            val next = current[part]
            if (next is MutableMap<*, *>) {
                current = next as MutableMap<String, Any?>
            } else {
                val newMap = LinkedHashMap<String, Any?>()
                current[part] = newMap
                current = newMap
            }
        }
        current[parts.last()] = value
    }

    /** Dumps the configuration to a canonical BLOCK formatted YAML string. */
    fun dumpYaml(): String {
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
            indent = 2
            indicatorIndent = 1
        }
        val yaml = Yaml(options)
        return yaml.dump(rawMap)
    }

    /** Atomically saves the configuration to the target file. */
    fun save(targetFile: File? = sourceFile) {
        val destination = targetFile ?: error("No target file specified for saving StConfig")
        destination.parentFile?.mkdirs()

        val tmpFile = File(destination.parentFile, "${destination.name}.tmp")
        tmpFile.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(dumpYaml())
        }

        try {
            Files.move(
                tmpFile.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                tmpFile.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            if (tmpFile.exists()) {
                tmpFile.delete()
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = 8000

        fun fromYaml(yamlText: String, sourceFile: File? = null): StConfig {
            val yaml = Yaml()
            val loaded = yaml.load<Any?>(yamlText)
            val map = if (loaded is Map<*, *>) {
                LinkedHashMap<String, Any?>().apply {
                    for ((k, v) in loaded) {
                        put(k.toString(), v)
                    }
                }
            } else {
                LinkedHashMap()
            }
            return StConfig(map, sourceFile)
        }

        fun fromStream(stream: InputStream, sourceFile: File? = null): StConfig {
            val yaml = Yaml()
            val loaded = stream.use { yaml.load<Any?>(it) }
            val map = if (loaded is Map<*, *>) {
                LinkedHashMap<String, Any?>().apply {
                    for ((k, v) in loaded) {
                        put(k.toString(), v)
                    }
                }
            } else {
                LinkedHashMap()
            }
            return StConfig(map, sourceFile)
        }

        fun fromFile(file: File): StConfig {
            return file.inputStream().use { fromStream(it, file) }
        }

        fun fromFileOrDefault(file: File, defaultProvider: () -> InputStream): StConfig {
            return if (file.exists()) {
                fromFile(file)
            } else {
                defaultProvider().use { fromStream(it, file) }
            }
        }
    }
}
