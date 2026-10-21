package me.rerere.ai.plugin

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Provider

/**
 * 微内核宿主：管理插件生命周期、按类型查询插件。
 *
 * 用法：
 * ```
 * val host = PluginHost()
 * host.register(LocalToolsPlugin())
 * host.register(OpenAIProviderPlugin())
 * host.start()
 *
 * // 收集所有工具
 * val allTools = host.getTools()
 *
 * // 关闭时
 * host.stop()
 * ```
 *
 * 设计目标：
 * - 按需加载：不用的插件不初始化，节省内存
 * - 可替换：换 agent 策略/工具集/模型提供商不需要改核心 loop
 * - 轻量：核心只有 ~100 行，不引入额外依赖
 */
class PluginHost {
    companion object {
        private const val TAG = "PluginHost"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val plugins = mutableListOf<Plugin>()
    private var started = false

    /** 注册插件。必须在 start() 之前调用。 */
    fun register(plugin: Plugin) {
        if (started) error("Cannot register plugin after host started")
        plugins.add(plugin)
    }

    /** 启动所有插件，按注册顺序初始化。 */
    suspend fun start() {
        if (started) return
        Log.i(TAG, "Starting ${plugins.size} plugins...")
        plugins.forEach { plugin ->
            runCatching {
                plugin.init(this)
                Log.d(TAG, "  ✓ ${plugin.id} v${plugin.version}")
            }.onFailure { e ->
                Log.w(TAG, "  ✗ ${plugin.id} failed to init: ${e.message}")
            }
        }
        started = true
        Log.i(TAG, "All plugins started.")
    }

    /** 停止所有插件，逆序清理。 */
    suspend fun stop() {
        if (!started) return
        plugins.reversed().forEach { plugin ->
            runCatching { plugin.dispose() }
                .onFailure { Log.w(TAG, "Failed to dispose ${plugin.id}: ${it.message}") }
        }
        plugins.clear()
        started = false
    }

    /** 按类型获取所有插件 */
    @Suppress("UNCHECKED_CAST")
    fun <T : Plugin> getPlugins(type: Class<T>): List<T> {
        return plugins.filterIsInstance(type)
    }

    /** 获取所有工具插件贡献的工具 */
    fun getTools(): List<Tool> {
        return plugins.filterIsInstance<ToolPlugin>()
            .flatMap { it.getTools() }
    }

    /** 获取所有模型提供商插件 */
    fun getProviders(): List<Provider<*>> {
        return plugins.filterIsInstance<ProviderPlugin>()
            .map { it.getProvider() }
    }

    /** 按 ID 查找插件 */
    fun findPlugin(id: String): Plugin? {
        return plugins.find { it.id == id }
    }

    /** 当前已注册插件数量 */
    val pluginCount: Int get() = plugins.size
}
