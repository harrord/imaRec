package site.webbing.audiorec

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

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
 * - [knowledgeBaseId]：目标知识库 ID（同时是主页当前选中 Tab 的 KB ID，双向绑定）
 * - [knowledgeBaseName]：知识库名称，仅用于界面展示，不参与接口调用
 * - [enabled]：是否在录音结束后自动上传到 IMA
 * - [allKnowledgeBases]：最近一次从服务端拉取到的全部知识库列表，持久化以便离线展示
 * - [activeTabs]：主页已展开为 Tab 的知识库列表（用户可添加/移除）
 * - [inspirationKbId] / [inspirationKbName]：灵感记录功能的目标知识库，独立于默认上传 KB，
 *   双击锁屏分段按钮进入灵感模式后，灵感期间的录音会保存并上传到此 KB
 */
data class ImaConfig(
    val enabled: Boolean = false,
    val clientId: String = "",
    val apiKey: String = "",
    val knowledgeBaseId: String = "",
    val knowledgeBaseName: String = "",
    val allKnowledgeBases: List<KnowledgeBaseOption> = emptyList(),
    val activeTabs: List<KnowledgeBaseOption> = emptyList(),
    val inspirationKbId: String = "",
    val inspirationKbName: String = "",
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
        Log.d(TAG, "update: start")
        val next = transform(_config.value)
        Log.d(TAG, "update: transform done, activeTabs=${next.activeTabs.size} selectedKbId=${next.knowledgeBaseId}")
        save(next)
        Log.d(TAG, "update: save done")
        _config.value = next
        Log.d(TAG, "update: stateFlow emitted")
    }

    /**
     * 用从服务端拉取的最新列表覆盖 [ImaConfig.allKnowledgeBases]，
     * 并同步更新 [ImaConfig.activeTabs] 与 [ImaConfig.knowledgeBaseName]：
     * - 已被用户添加为 Tab 的 KB，按最新 Name 更新显示名
     * - 云端已删除的 KB，从 activeTabs 中移除；若被移除的恰是当前选中 KB，清空选中态
     * - 当前 [knowledgeBaseId] 在最新列表中存在时，刷新 [knowledgeBaseName]
     */
    fun applyAllKnowledgeBases(list: List<KnowledgeBaseOption>) {
        update { cfg ->
            val byId = list.associateBy { it.id }
            val newActiveTabs = cfg.activeTabs.mapNotNull { tab ->
                byId[tab.id]?.let { tab.copy(name = it.name) }
            }
            val newSelectedName = cfg.knowledgeBaseId.takeIf { it.isNotBlank() }
                ?.let { byId[it]?.name }
                ?: cfg.knowledgeBaseName
            val newSelectedId = cfg.knowledgeBaseId.takeIf { byId.containsKey(it) } ?: ""
            cfg.copy(
                allKnowledgeBases = list,
                activeTabs = newActiveTabs,
                knowledgeBaseId = newSelectedId,
                knowledgeBaseName = if (newSelectedId.isBlank()) "" else newSelectedName,
            )
        }
    }

    /**
     * 添加一个知识库为主页 Tab，并切换为当前选中。
     * 若该 KB 已在 activeTabs 中，则仅切换选中态。
     */
    fun addTabAndSelect(kb: KnowledgeBaseOption) {
        Log.d(TAG, "addTabAndSelect: kb=${kb.id} name=${kb.name}")
        update { cfg ->
            val newActiveTabs = if (cfg.activeTabs.any { it.id == kb.id }) {
                cfg.activeTabs
            } else {
                cfg.activeTabs + kb
            }
            cfg.copy(
                activeTabs = newActiveTabs,
                knowledgeBaseId = kb.id,
                knowledgeBaseName = kb.name,
            )
        }
    }

    /**
     * 从主页移除指定 Tab（不删除服务端知识库）。
     * 若被移除的是当前选中 Tab：
     * - 若仍有其他 Tab，切换到第一个
     * - 否则清空选中态（主页回到无 Tab 状态）
     */
    fun removeTab(kbId: String) {
        update { cfg ->
            val newActiveTabs = cfg.activeTabs.filterNot { it.id == kbId }
            val newSelected = if (cfg.knowledgeBaseId == kbId) {
                newActiveTabs.firstOrNull() ?: KnowledgeBaseOption("", "")
            } else {
                newActiveTabs.firstOrNull { it.id == cfg.knowledgeBaseId }
                    ?: KnowledgeBaseOption("", "")
            }
            cfg.copy(
                activeTabs = newActiveTabs,
                knowledgeBaseId = newSelected.id,
                knowledgeBaseName = newSelected.name,
            )
        }
    }

    /** 切换当前选中的 Tab（同步更新默认 KB ID/Name）。 */
    fun selectTab(kbId: String) {
        update { cfg ->
            val tab = cfg.activeTabs.firstOrNull { it.id == kbId }
                ?: return@update cfg
            cfg.copy(knowledgeBaseId = tab.id, knowledgeBaseName = tab.name)
        }
    }

    /**
     * 设置灵感记录功能的目标知识库。独立于默认上传 KB 与主页 Tab 选中态，
     * 仅更新 [ImaConfig.inspirationKbId] / [ImaConfig.inspirationKbName]。
     */
    fun setInspirationKb(id: String, name: String) {
        update { it.copy(inspirationKbId = id, inspirationKbName = name) }
    }

    private fun load(): ImaConfig = ImaConfig(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        clientId = prefs.getString(KEY_CLIENT_ID, "").orEmpty(),
        apiKey = prefs.getString(KEY_API_KEY, "").orEmpty(),
        knowledgeBaseId = prefs.getString(KEY_KB_ID, "").orEmpty(),
        knowledgeBaseName = prefs.getString(KEY_KB_NAME, "").orEmpty(),
        allKnowledgeBases = readKbList(prefs.getString(KEY_ALL_KB_LIST, null)),
        activeTabs = readKbList(prefs.getString(KEY_ACTIVE_TABS, null)),
        inspirationKbId = prefs.getString(KEY_INSPIRATION_KB_ID, "").orEmpty(),
        inspirationKbName = prefs.getString(KEY_INSPIRATION_KB_NAME, "").orEmpty(),
    )

    private fun save(config: ImaConfig) {
        prefs.edit().apply {
            putBoolean(KEY_ENABLED, config.enabled)
            putString(KEY_CLIENT_ID, config.clientId)
            putString(KEY_API_KEY, config.apiKey)
            putString(KEY_KB_ID, config.knowledgeBaseId)
            putString(KEY_KB_NAME, config.knowledgeBaseName)
            putString(KEY_ALL_KB_LIST, writeKbList(config.allKnowledgeBases))
            putString(KEY_ACTIVE_TABS, writeKbList(config.activeTabs))
            putString(KEY_INSPIRATION_KB_ID, config.inspirationKbId)
            putString(KEY_INSPIRATION_KB_NAME, config.inspirationKbName)
            apply()
        }
    }

    private fun readKbList(json: String?): List<KnowledgeBaseOption> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id")
                if (id.isBlank()) null else KnowledgeBaseOption(id = id, name = obj.optString("name"))
            }
        }.getOrElse { emptyList() }
    }

    private fun writeKbList(list: List<KnowledgeBaseOption>): String {
        val arr = JSONArray()
        list.forEach { kb ->
            arr.put(JSONObject().apply {
                put("id", kb.id)
                put("name", kb.name)
            })
        }
        return arr.toString()
    }

    companion object {
        private const val TAG = "ImaSettings"

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
        private const val KEY_ALL_KB_LIST = "all_kb_list"
        private const val KEY_ACTIVE_TABS = "active_tabs"
        private const val KEY_INSPIRATION_KB_ID = "inspiration_kb_id"
        private const val KEY_INSPIRATION_KB_NAME = "inspiration_kb_name"
    }
}
