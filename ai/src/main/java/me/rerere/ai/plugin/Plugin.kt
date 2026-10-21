package me.rerere.ai.plugin

import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Provider

/**
 * 微内核插件基础接口。
 *
 * 所有可插拔模块（工具集、模型提供商、技能加载器、记忆后端等）都实现这个接口。
 * PluginHost 负责生命周期管理和按类型查询。
 *
 * 设计参考：DeepSeek Harness 的 Cordis 微内核 + Hermes 的技能系统。
 */
interface Plugin {
    /** 插件唯一标识，如 "local-tools"、"openai-provider" */
    val id: String

    /** 人类可读名称 */
    val name: String get() = id

    /** 版本号，用于调试和缓存失效 */
    val version: String get() = "1.0.0"

    /**
     * 插件初始化。在 PluginHost 启动时调用一次。
     * 重写此方法做资源加载、连接建立等操作。
     */
    suspend fun init(host: PluginHost) {}

    /**
     * 插件清理。在 PluginHost 关闭时调用。
     * 重写此方法释放资源、关闭连接等。
     */
    suspend fun dispose() {}
}

/**
 * 工具插件：提供一组 Tool。
 *
 * 每个插件可以贡献多个工具。GenerationLoop 启动时从所有 ToolPlugin 收集工具列表。
 */
interface ToolPlugin : Plugin {
    /** 该插件贡献的工具列表 */
    fun getTools(): List<Tool>
}

/**
 * 模型提供商插件：提供一个 Provider 实现。
 *
 * ProviderManager 启动时从所有 ProviderPlugin 注册提供商。
 */
interface ProviderPlugin : Plugin {
    /** 该插件提供的 Provider 实例 */
    fun getProvider(): Provider<*>
}

/**
 * 技能加载器插件：提供技能内容。
 *
 * SkillManager 启动时从所有 SkillLoaderPlugin 收集技能元数据。
 * 内置的是文件系统加载器，未来可以加远程加载器、向量检索加载器等。
 */
interface SkillLoaderPlugin : Plugin {
    /** 返回所有可用技能的元数据 */
    suspend fun listSkills(): List<SkillDescriptor>

    /** 读取技能正文 */
    suspend fun readSkillBody(skillId: String): String?

    /** 读取技能目录下的指定文件 */
    suspend fun readSkillFile(skillId: String, path: String): String?
}

/**
 * 技能描述符，跨插件统一格式。
 */
data class SkillDescriptor(
    val id: String,
    val name: String,
    val description: String,
    val autoLoad: Boolean = false,
    val autoLoadPath: String? = null,
)
