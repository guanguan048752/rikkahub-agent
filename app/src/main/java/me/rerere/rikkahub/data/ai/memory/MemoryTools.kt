package me.rerere.rikkahub.data.ai.memory

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * 记忆工具：让 LLM 可以主动存和回忆跨会话信息。
 *
 * 两个工具：
 * - memory_save：把重要的用户信息、偏好、决策存下来
 * - memory_recall：根据关键词回忆之前存的信息
 *
 * 用法：对话中遇到用户说"记住我喜欢..."或者重要决策时，主动调 memory_save。
 * 下次对话开头，可以先调 memory_recall 回忆用户的偏好。
 */
fun createMemoryTools(memoryStore: MemoryStore): List<Tool> {
    return listOf(
        // memory_save：存一条记忆
        Tool(
            name = "memory_save",
            description = """
                Save an important fact, user preference, or decision to long-term memory.
                Call this when the user tells you something worth remembering across conversations:
                personal preferences, important facts about them, ongoing projects, or decisions made.
                Don't save trivial one-off questions or things that are obvious from context.
            """.trimIndent(),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", "The fact or preference to remember, written as a clear statement.")
                        })
                        put("tags", buildJsonObject {
                            put("type", "string")
                            put("description", "Optional comma-separated tags to categorize this memory (e.g. \"preference, project, health\").")
                        })
                    },
                    required = listOf("content")
                )
            },
            execute = { args ->
                val content = args.jsonObject["content"]?.jsonPrimitive?.content?.trim()
                    ?: return@Tool listOf(UIMessagePart.Text("Error: missing 'content' argument."))

                if (content.length < 5) {
                    return@Tool listOf(UIMessagePart.Text("Error: content too short to be useful as a memory."))
                }

                val tagsStr = args.jsonObject["tags"]?.jsonPrimitive?.content ?: ""
                val tags = tagsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }

                memoryStore.saveMemory(content, tags)
                listOf(UIMessagePart.Text("Memory saved. I'll remember this in future conversations."))
            }
        ),

        // memory_recall：回忆记忆
        Tool(
            name = "memory_recall",
            description = """
                Recall previously saved memories about the user.
                Call this at the start of a conversation to remember the user's preferences and history,
                or when you need context from past conversations.
                Search by keywords related to what you need to remember.
            """.trimIndent(),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("query", buildJsonObject {
                            put("type", "string")
                            put("description", "Keywords describing what you want to recall (e.g. \"user preferences, project, health\").")
                        })
                    },
                    required = listOf("query")
                )
            },
            execute = { args ->
                val query = args.jsonObject["query"]?.jsonPrimitive?.content?.trim()
                    ?: return@Tool listOf(UIMessagePart.Text("Error: missing 'query' argument."))

                val results = memoryStore.searchMemories(query, limit = 10)
                if (results.isEmpty()) {
                    listOf(UIMessagePart.Text("No matching memories found. This might be the first time discussing this topic."))
                } else {
                    val text = buildString {
                        appendLine("Found ${results.size} relevant memories:")
                        appendLine()
                        results.forEach { entry ->
                            appendLine(entry.formatForPrompt())
                        }
                    }
                    listOf(UIMessagePart.Text(text))
                }
            }
        ),
    )
}
