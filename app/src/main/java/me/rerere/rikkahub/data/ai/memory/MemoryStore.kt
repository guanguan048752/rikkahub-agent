package me.rerere.rikkahub.data.ai.memory

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 跨会话记忆存储。
 *
 * 三层记忆架构的最底层：长期记忆。
 * - 短期：当前会话的消息（GenerationLoop 管）
 * - 中期：技能文档库（SkillManager 管）
 * - 长期：用户事实、偏好、历史决策（MemoryStore 管）
 *
 * 用文件系统存储，每条记忆一个 JSON 文件，简单可靠，不需要数据库。
 */
class MemoryStore(private val context: Context) {
    companion object {
        private const val MEMORY_DIR = "memory"
        private const val MAX_MEMORIES = 500
        private val json = Json { ignoreUnknownKeys = true }
    }

    private fun getMemoryDir(): File {
        val dir = File(context.filesDir, MEMORY_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 保存一条记忆 */
    fun saveMemory(content: String, tags: List<String> = []): MemoryEntry {
        val entry = MemoryEntry(
            id = UUID.randomUUID().toString(),
            content = content.trim(),
            tags = tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() },
            createdAt = System.currentTimeMillis(),
        )
        val file = File(getMemoryDir(), "${entry.id}.json")
        file.writeText(json.encodeToString(MemoryEntry.serializer(), entry))

        // 超出上限时清理最旧的
        trimIfNeeded()
        return entry
    }

    /** 按关键词搜索记忆 */
    fun searchMemories(query: String, limit: Int = 10): List<MemoryEntry> {
        val dir = getMemoryDir()
        val allFiles = dir.listFiles { f -> f.extension == "json" } ?: return emptyList()

        val queryLower = query.lowercase()
        val queryWords = queryLower.split(Regex("\\s+")).filter { it.length > 1 }

        return allFiles.mapNotNull { file ->
            runCatching {
                json.decodeFromString(MemoryEntry.serializer(), file.readText())
            }.getOrNull()
        }.map { entry ->
            // 简单打分：内容匹配 + 标签匹配
            var score = 0
            queryWords.forEach { word ->
                if (entry.content.lowercase().contains(word)) score += 2
                if (entry.tags.any { it.contains(word) }) score += 3
            }
            score to entry
        }.filter { it.first > 0 }
            .sortedByDescending { it.first }
            .take(limit)
            .map { it.second }
    }

    /** 列出所有记忆（按时间倒序） */
    fun listMemories(): List<MemoryEntry> {
        val dir = getMemoryDir()
        val allFiles = dir.listFiles { f -> f.extension == "json" } ?: return emptyList()
        return allFiles.mapNotNull { file ->
            runCatching {
                json.decodeFromString(MemoryEntry.serializer(), file.readText())
            }.getOrNull()
        }.sortedByDescending { it.createdAt }
    }

    /** 删除一条记忆 */
    fun deleteMemory(id: String): Boolean {
        val file = File(getMemoryDir(), "$id.json")
        return file.delete()
    }

    /** 清空所有记忆 */
    fun clearAll() {
        getMemoryDir().listFiles()?.forEach { it.delete() }
    }

    private fun trimIfNeeded() {
        val all = listMemories()
        if (all.size > MAX_MEMORIES) {
            all.drop(MAX_MEMORIES).forEach { entry ->
                File(getMemoryDir(), "${entry.id}.json").delete()
            }
        }
    }
}

@Serializable
data class MemoryEntry(
    val id: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val createdAt: Long,
) {
    fun formatForPrompt(): String {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(createdAt))
        val tagStr = if (tags.isNotEmpty()) " [${tags.joinToString(", ")}]" else ""
        return "- ($dateStr)$tagStr $content"
    }
}
