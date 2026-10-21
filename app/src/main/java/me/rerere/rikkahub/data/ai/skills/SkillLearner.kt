package me.rerere.rikkahub.data.ai.skills

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.SkillManager
import java.io.File

/**
 * 自动技能提炼器。
 *
 * 参考 Hermes Agent 的自改进学习循环：对话结束后，后台调用 LLM 分析整个交互，
 * 判断有没有可复用的模式，有的话自动生成 SKILL.md 存到技能目录。
 *
 * 设计原则：
 * - 异步后台运行，不阻塞用户对话
 * - 有触发阈值：对话太短/太简单就不提炼，省 token
 * - 保守策略：LLM 说"没有可复用模式"就直接跳过，不硬造技能
 * - 人工可编辑：生成的技能跟手动创建的技能格式完全一致，用户可以在 App 里改
 */
class SkillLearner(
    private val context: Context,
    private val skillManager: SkillManager,
) {
    companion object {
        private const val TAG = "SkillLearner"

        /** 最少对话轮数才触发提炼 */
        private const val MIN_TURNS_TO_LEARN = 3

        /** 最少工具调用次数才触发提炼 */
        private const val MIN_TOOL_CALLS_TO_LEARN = 2

        /** 最大对话历史长度（字符），避免 token 爆炸 */
        private const val MAX_HISTORY_CHARS = 8000
    }

    /**
     * 分析一段对话，看能不能提炼出新技能。
     *
     * @param messages 完整对话历史
     * @param provider 用来做提炼的 Provider
     * @param providerSetting Provider 配置
     * @param model 用哪个模型做提炼（建议用便宜快的模型）
     * @return 如果成功提炼出技能，返回技能名称；否则返回 null
     */
    suspend fun learnFromConversation(
        messages: List<UIMessage>,
        provider: Provider<ProviderSetting>,
        providerSetting: ProviderSetting,
        model: me.rerere.ai.provider.Model,
    ): String? = withContext(Dispatchers.IO) {
        // 1. 阈值判断：太短/太简单就不学
        val userTurns = messages.count { it.role == me.rerere.ai.core.MessageRole.USER }
        val toolCallCount = messages.count { msg ->
            msg.parts.any { it is me.rerere.ai.ui.UIMessagePart.ToolCall }
        }

        if (userTurns < MIN_TURNS_TO_LEARN) {
            Log.d(TAG, "Skipping: only $userTurns user turns (min $MIN_TURNS_TO_LEARN)")
            return@withContext null
        }
        if (toolCallCount < MIN_TOOL_CALLS_TO_LEARN) {
            Log.d(TAG, "Skipping: only $toolCallCount tool calls (min $MIN_TOOL_CALLS_TO_LEARN)")
            return@withContext null
        }

        // 2. 构造提炼 prompt
        val historyText = buildHistorySummary(messages)
        val prompt = buildLearningPrompt(historyText)

        // 3. 调用 LLM 分析
        val result: TextGenerationResult = try {
            provider.generateText(
                providerSetting = providerSetting,
                messages = listOf(
                    UIMessage(
                        role = me.rerere.ai.core.MessageRole.USER,
                        parts = listOf(me.rerere.ai.ui.UIMessagePart.Text(prompt))
                    )
                ),
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.3f,
                    maxTokens = 1024,
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Skill learning LLM call failed: ${e.message}")
            return@withContext null
        }

        // 4. 解析结果
        val responseText = result.message.parts
            .filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>()
            .joinToString { it.text }

        val skill = parseSkillFromResponse(responseText) ?: run {
            Log.d(TAG, "No reusable pattern found in this conversation.")
            return@withContext null
        }

        // 5. 写入文件
        saveSkill(skill)
        Log.i(TAG, "Learned new skill: ${skill.name}")
        skill.name
    }

    /** 把对话历史压缩成 LLM 能读的摘要文本 */
    private fun buildHistorySummary(messages: List<UIMessage>): String {
        val sb = StringBuilder()
        messages.forEach { msg ->
            val role = when (msg.role) {
                me.rerere.ai.core.MessageRole.USER -> "User"
                me.rerere.ai.core.MessageRole.ASSISTANT -> "Assistant"
                else -> "System"
            }
            sb.appendLine("[$role]")
            msg.parts.forEach { part ->
                when (part) {
                    is me.rerere.ai.ui.UIMessagePart.Text -> {
                        val text = part.text.take(500)
                        sb.appendLine(text)
                    }
                    is me.rerere.ai.ui.UIMessagePart.ToolCall -> {
                        sb.appendLine("[Tool call: ${part.name}]")
                    }
                    is me.rerere.ai.ui.UIMessagePart.ToolResult -> {
                        sb.appendLine("[Tool result: ${part.content.take(200)}]")
                    }
                    else -> {}
                }
            }
            sb.appendLine()
        }
        // 截断，防止 token 爆炸
        return if (sb.length > MAX_HISTORY_CHARS) {
            sb.substring(0, MAX_HISTORY_CHARS) + "\n...(truncated)"
        } else {
            sb.toString()
        }
    }

    /** 构造给 LLM 的提炼指令 */
    private fun buildLearningPrompt(history: String): String {
        return """
            You are analyzing a conversation between a user and an AI agent on an Android phone.

            Your job: determine if this conversation contains a **reusable procedural pattern** that could be turned into a "skill" — a set of instructions the agent can reuse in future conversations.

            A good skill is:
            - A repeatable workflow the user does often (e.g. "summarize notifications and act on them", "check weather and plan outfit")
            - Something that requires multiple tool calls in a specific sequence
            - Not a one-off question or a trivial fact lookup

            If there IS a reusable pattern, respond with a JSON object like this:
            ```json
            {
              "has_skill": true,
              "name": "short-skill-name-kebab-case",
              "description": "One sentence: what this skill does and when to use it.",
              "body": "The full skill instructions in Markdown. Write step-by-step what the agent should do when this skill is activated."
            }
            ```

            If there is NO reusable pattern, respond with:
            ```json
            {"has_skill": false}
            ```

            Here is the conversation:
            ---
            $history
            ---

            Respond with ONLY the JSON, no extra text.
        """.trimIndent()
    }

    /** 从 LLM 响应里解析出技能结构 */
    private fun parseSkillFromResponse(text: String): LearnedSkill? {
        // 提取 JSON 块
        val jsonMatch = Regex("```json\\s*([\\s\\S]*?)```").find(text)
            ?: Regex("\\{[\\s\\S]*\"has_skill\"[\\s\\S]*\\}").find(text)
            ?: return null

        val jsonStr = jsonMatch.groupValues.getOrNull(1) ?: jsonMatch.value

        return try {
            val json = Json.parseToJsonElement(jsonStr).jsonObject
            val hasSkill = json["has_skill"]?.jsonPrimitive?.content?.toBoolean() ?: false
            if (!hasSkill) return null

            LearnedSkill(
                name = json["name"]?.jsonPrimitive?.content?.trim() ?: return null,
                description = json["description"]?.jsonPrimitive?.content?.trim() ?: "",
                body = json["body"]?.jsonPrimitive?.content?.trim() ?: "",
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse skill JSON: ${e.message}")
            null
        }
    }

    /** 把提炼出的技能写入文件系统 */
    private fun saveSkill(skill: LearnedSkill) {
        val skillsDir = skillManager.getSkillsDir()
        // 安全处理名称：只允许小写字母、数字、连字符
        val safeName = skill.name.lowercase()
            .replace(Regex("[^a-z0-9-]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(50)

        if (safeName.isBlank()) return

        val skillDir = File(skillsDir, safeName)
        skillDir.mkdirs()

        val skillMd = skillDir.resolve("SKILL.md")
        val content = buildString {
            appendLine("---")
            appendLine("name: $safeName")
            appendLine("description: ${skill.description}")
            appendLine("auto_load: false")
            appendLine("source: auto-learned")
            appendLine("---")
            appendLine()
            appendLine(skill.body)
        }
        skillMd.writeText(content)
    }

    /** 提炼出的技能结构 */
    data class LearnedSkill(
        val name: String,
        val description: String,
        val body: String,
    )
}
