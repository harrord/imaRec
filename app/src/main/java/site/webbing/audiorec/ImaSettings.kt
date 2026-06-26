package site.webbing.audiorec

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 知识库选择项，用于设置页从服务端拉取后展示。
 */
data class KnowledgeBaseOption(
    val id: String,
    val name: String,
)

/**
 * IMA 知识库上传配置。
 *
 * 对应 https://ima.qq.com/agent-interface 申请的 OpenAPI 凭证：
 * - [clientId] / [apiKey]：用于请求头 ima-openapi-clientid / ima-openapi-apikey
 * - [knowledgeBaseId]：目标知识库 ID
 * - [knowledgeBaseName]：知识库名称，仅用于界面展示，不参与接口调用
 * - [enabled]：是否在录音结束后自动上传到 IMA
 */
data class ImaConfig(
    val enabled: Boolean = false,
    val clientId: String = "",
    val apiKey: String = "",
    val knowledgeBaseId: String = "",
    val knowledgeBaseName: String = "",
) {
    /** 判断配置是否完整，可发起一次上传。 */
    val isConfigured: Boolean
        get() = clientId.isNotBlank() &&
            apiKey.isNotBlank() &&
            knowledgeBaseId.isNotBlank()
}

/**
 * 基于 SharedPreferences 的 IMA 配置存储，并向 UI 暴露 [StateFlow]。
 *
 * 使用单例，确保 Service / ViewModel / SettingsScreen 共享同一 [StateFlow]，
 * 在任意位置修改配置后，其他持有者能立即收到更新。
 */
class ImaSettings private constructor(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(load())
    val config: StateFlow<ImaConfig> = _config.asStateFlow()

    fun update(transform: (ImaConfig) -> ImaConfig) {
        val next = transform(_config.value)
        save(next)
        _config.value = next
    }

    private fun load(): ImaConfig = ImaConfig(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        clientId = prefs.getString(KEY_CLIENT_ID, "").orEmpty(),
        apiKey = prefs.getString(KEY_API_KEY, "").orEmpty(),
        knowledgeBaseId = prefs.getString(KEY_KB_ID, "").orEmpty(),
        knowledgeBaseName = prefs.getString(KEY_KB_NAME, "").orEmpty(),
    )

    private fun save(config: ImaConfig) {
        prefs.edit().apply {
            putBoolean(KEY_ENABLED, config.enabled)
            putString(KEY_CLIENT_ID, config.clientId)
            putString(KEY_API_KEY, config.apiKey)
            putString(KEY_KB_ID, config.knowledgeBaseId)
            putString(KEY_KB_NAME, config.knowledgeBaseName)
            apply()
        }
    }

    companion object {
        @Volatile
        private var instance: ImaSettings? = null

        fun get(context: Context): ImaSettings =
            instance ?: synchronized(this) {
                instance ?: ImaSettings(context.applicationContext).also { instance = it }
            }

        private const val PREFS_NAME = "ima_settings"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_KB_ID = "knowledge_base_id"
        private const val KEY_KB_NAME = "knowledge_base_name"
    }
}
