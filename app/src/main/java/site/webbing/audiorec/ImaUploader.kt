package site.webbing.audiorec

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * IMA 上传异常，[message] 可直接展示给用户。
 */
private class ImaException(message: String) : Exception(message)

/** create_media 返回的 COS 临时凭证。 */
private data class CosCredential(
    val token: String,
    val secretId: String,
    val secretKey: String,
    val startTime: String,
    val expiredTime: String,
    val bucketName: String,
    val region: String,
    val cosKey: String,
)

/**
 * 复现 ima-skill 中「上传文件到知识库」的完整流程：
 *
 * 1. check_repeated_names（重名检查，重复时自动追加时间戳）
 * 2. create_media（获取 COS 临时凭证与 media_id）
 * 3. COS PUT 上传（HMAC-SHA1 签名）
 * 4. add_knowledge（写入知识库条目）
 *
 * 录音文件固定为 m4a（media_type=15，content_type=audio/x-m4a）。
 *
 * 使用自带 [CoroutineScope]（IO + SupervisorJob），调用方无需持有协程作用域，
 * 上传可在 Service 销毁后继续运行。
 */
class ImaUploader private constructor(
    private val context: Context,
    private val settings: ImaSettings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun logD(msg: String) = Log.d(TAG, msg)
    private fun logE(msg: String, t: Throwable? = null) = Log.e(TAG, msg, t)

    /**
     * 异步上传一段录音文件到 IMA 知识库。
     * - 未开启自动上传或配置不完整时直接返回。
     * - 进度与结果通过 [ImaUploadStateStore] 暴露给 UI。
     */
    fun uploadRecording(file: File) {
        val config = settings.config.value
        if (!config.enabled) return
        if (!config.isConfigured) {
            ImaUploadStateStore.set(
                ImaUploadStatus.Failed(
                    file.name,
                    "IMA 配置不完整，请在设置中填写 Client ID、API Key 和知识库 ID",
                ),
            )
            return
        }
        // 诊断日志：把实际使用的配置打出来（KB ID 用引号包裹并标注长度，
        // 用于暴露首尾空白、不可见字符等常见复制粘贴问题）
        logD(
            "uploadRecording start: file=${file.name} size=${file.length()}\n" +
                "  clientId=\"${config.clientId}\" (len=${config.clientId.length}, trimmed=${config.clientId.trim().length})\n" +
                "  apiKey provided=${config.apiKey.isNotBlank()} (len=${config.apiKey.length}, trimmed=${config.apiKey.trim().length})\n" +
                "  knowledgeBaseId=\"${config.knowledgeBaseId}\" (len=${config.knowledgeBaseId.length}, trimmed=${config.knowledgeBaseId.trim().length})\n" +
                "  kbIdHasLeadingOrTrailingWhitespace=${config.knowledgeBaseId != config.knowledgeBaseId.trim()}"
        )
        scope.launch {
            try {
                uploadInternal(file, config)
            } catch (e: ImaException) {
                logE("upload failed (ImaException): ${e.message}")
                ImaUploadStateStore.set(ImaUploadStatus.Failed(file.name, e.message ?: "上传失败"))
            } catch (e: Exception) {
                logE("upload failed (unexpected)", e)
                ImaUploadStateStore.set(
                    ImaUploadStatus.Failed(file.name, "上传失败：${e.localizedMessage ?: "未知错误"}"),
                )
            }
        }
    }

    /**
     * 拉取当前账号下可添加内容的知识库列表（用于设置页选择目标知识库）。
     * 仅依赖 Client ID + API Key，不要求 knowledgeBaseId 已填。
     * 网络请求在 IO 线程执行，调用方可安全在主线程调用。
     */
    suspend fun listAddableKnowledgeBases(): List<KnowledgeBaseOption> = withContext(Dispatchers.IO) {
        val config = settings.config.value
        if (config.clientId.isBlank() || config.apiKey.isBlank()) {
            throw ImaException("请先填写 Client ID 和 API Key")
        }
        val result = mutableListOf<KnowledgeBaseOption>()
        var cursor = ""
        do {
            val body = JSONObject().apply {
                put("cursor", cursor)
                put("limit", 50)
            }
            logD("→ listAddableKnowledgeBases: cursor=\"$cursor\"")
            val data = postJson("openapi/wiki/v1/get_addable_knowledge_base_list", body, config)
            val list = data.optJSONArray("addable_knowledge_base_list") ?: JSONArray()
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val id = item.optString("id")
                val name = item.optString("name")
                if (id.isNotBlank()) result.add(KnowledgeBaseOption(id = id, name = name))
            }
            if (data.optBoolean("is_end", true)) break
            cursor = data.optString("next_cursor")
        } while (cursor.isNotBlank())
        logD("listAddableKnowledgeBases done: count=${result.size}")
        result
    }

    private fun uploadInternal(file: File, config: ImaConfig) {
        ImaUploadStateStore.set(ImaUploadStatus.Uploading(file.name))

        // 录音文件固定为 m4a（与 RecordingFileManager 生成规则一致）
        val fileName = file.name
        val fileExt = "m4a"
        val contentType = "audio/x-m4a"
        val mediaType = 15
        val fileSize = file.length()
        logD("uploadInternal: fileName=$fileName fileExt=$fileExt contentType=$contentType mediaType=$mediaType fileSize=$fileSize")

        // Step 1: check_repeated_names —— 重复时自动追加时间戳，保持两者
        logD("Step 1: check_repeated_names")
        val finalName = checkRepeatedNames(fileName, mediaType, config)
        logD("Step 1 done: finalName=$finalName")

        // Step 2: create_media —— 获取 COS 凭证与 media_id
        logD("Step 2: create_media")
        val (mediaId, cosCred) = createMedia(finalName, fileSize, contentType, fileExt, config)
        logD("Step 2 done: mediaId=$mediaId bucket=${cosCred.bucketName} region=${cosCred.region} cosKey=${cosCred.cosKey}")

        // Step 3: COS PUT 上传
        logD("Step 3: COS PUT upload")
        uploadToCos(file, cosCred, contentType)
        logD("Step 3 done")

        // Step 4: add_knowledge —— title 必须等于 file_name
        logD("Step 4: add_knowledge")
        addKnowledge(mediaId, mediaType, finalName, fileSize, cosCred, config)
        logD("Step 4 done")

        ImaUploadStateStore.set(ImaUploadStatus.Success(finalName))
    }

    // ── Step 1: 重名检查 ──

    private fun checkRepeatedNames(fileName: String, mediaType: Int, config: ImaConfig): String {
        val body = JSONObject().apply {
            put("params", JSONArray().apply {
                put(JSONObject().apply {
                    put("name", fileName)
                    put("media_type", mediaType)
                })
            })
            put("knowledge_base_id", config.knowledgeBaseId)
        }
        val data = postJson("openapi/wiki/v1/check_repeated_names", body, config)
        val results = data.optJSONArray("results") ?: return fileName

        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            if (item.optString("name") == fileName && item.optBoolean("is_repeated", false)) {
                // 同名已存在：追加 _YYYYMMDDHHmmss，格式与 skill 规范一致
                val timestamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
                val dotIndex = fileName.lastIndexOf('.')
                return if (dotIndex > 0) {
                    "${fileName.substring(0, dotIndex)}_$timestamp.${fileName.substring(dotIndex + 1)}"
                } else {
                    "${fileName}_$timestamp"
                }
            }
        }
        return fileName
    }

    // ── Step 2: 创建媒体 ──

    private fun createMedia(
        fileName: String,
        fileSize: Long,
        contentType: String,
        fileExt: String,
        config: ImaConfig,
    ): Pair<String, CosCredential> {
        val body = JSONObject().apply {
            put("file_name", fileName)
            put("file_size", fileSize)
            put("content_type", contentType)
            put("knowledge_base_id", config.knowledgeBaseId)
            put("file_ext", fileExt)
        }
        val data = postJson("openapi/wiki/v1/create_media", body, config)

        val mediaId = data.optString("media_id")
        if (mediaId.isBlank()) {
            throw ImaException("create_media 未返回 media_id")
        }
        val credJson = data.optJSONObject("cos_credential")
            ?: throw ImaException("create_media 未返回 cos_credential")

        val cred = CosCredential(
            token = credJson.optString("token"),
            secretId = credJson.optString("secret_id"),
            secretKey = credJson.optString("secret_key"),
            startTime = credJson.optString("start_time"),
            expiredTime = credJson.optString("expired_time"),
            bucketName = credJson.optString("bucket_name"),
            region = credJson.optString("region"),
            cosKey = credJson.optString("cos_key"),
        )
        if (cred.token.isBlank() || cred.bucketName.isBlank() || cred.region.isBlank() || cred.cosKey.isBlank()) {
            throw ImaException("create_media 返回的 COS 凭证不完整")
        }
        return mediaId to cred
    }

    // ── Step 3: COS 上传（PUT Object + HMAC-SHA1 签名） ──

    private fun uploadToCos(file: File, cred: CosCredential, contentType: String) {
        val fileBytes = file.readBytes()
        val hostname = "${cred.bucketName}.cos.${cred.region}.myqcloud.com"
        val pathname = "/${cred.cosKey}"
        logD("COS PUT: https://$hostname$pathname (size=${fileBytes.size} contentType=$contentType)")

        val signHeaders = mapOf(
            "content-length" to fileBytes.size.toString(),
            "host" to hostname,
        )
        val authorization = buildCosAuthorization(
            secretId = cred.secretId,
            secretKey = cred.secretKey,
            method = "PUT",
            pathname = pathname,
            headers = signHeaders,
            startTime = cred.startTime,
            expiredTime = cred.expiredTime,
        )

        val url = URL("https://$hostname$pathname")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = 60_000
            readTimeout = 300_000
            setRequestProperty("Content-Type", contentType)
            setRequestProperty("Content-Length", fileBytes.size.toString())
            setRequestProperty("Authorization", authorization)
            setRequestProperty("x-cos-security-token", cred.token)
            doOutput = true
        }
        try {
            conn.outputStream.use { it.write(fileBytes) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val errText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                logE("COS PUT failed: HTTP $code\n  error=$errText")
                throw ImaException("COS 上传失败（HTTP $code）：$errText")
            }
            logD("COS PUT success: HTTP $code")
        } finally {
            conn.disconnect()
        }
    }

    // ── Step 4: 添加知识 ──

    private fun addKnowledge(
        mediaId: String,
        mediaType: Int,
        fileName: String,
        fileSize: Long,
        cosCred: CosCredential,
        config: ImaConfig,
    ) {
        val body = JSONObject().apply {
            put("media_type", mediaType)
            put("media_id", mediaId)
            put("title", fileName)
            put("knowledge_base_id", config.knowledgeBaseId)
            put("file_info", JSONObject().apply {
                put("cos_key", cosCred.cosKey)
                put("file_size", fileSize)
                put("file_name", fileName)
            })
        }
        postJson("openapi/wiki/v1/add_knowledge", body, config)
    }

    // ── HTTP / 工具方法 ──

    private fun postJson(apiPath: String, body: JSONObject, config: ImaConfig): JSONObject {
        val url = URL("${BASE_URL}/$apiPath")
        val bodyString = body.toString()
        logD("→ POST $url\n  body=$bodyString")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("ima-openapi-clientid", config.clientId)
            setRequestProperty("ima-openapi-apikey", config.apiKey)
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }
        try {
            conn.outputStream.use { it.write(bodyString.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val responseText = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            logD("← HTTP $code $apiPath\n  response=$responseText")
            val resp = JSONObject(responseText)
            if (resp.optInt("code", -1) != 0) {
                throw ImaException(resp.optString("msg", "IMA 接口返回错误：$apiPath"))
            }
            return resp.optJSONObject("data") ?: JSONObject()
        } catch (e: org.json.JSONException) {
            logE("response JSON parse failed for $apiPath: ${e.message}")
            throw ImaException("响应解析失败：${e.message ?: "非法 JSON"}")
        } finally {
            conn.disconnect()
        }
    }

    /** COS 鉴权串，参考 https://cloud.tencent.com/document/product/436/7778 */
    private fun buildCosAuthorization(
        secretId: String,
        secretKey: String,
        method: String,
        pathname: String,
        headers: Map<String, String>,
        startTime: String,
        expiredTime: String,
    ): String {
        val keyTime = "$startTime;$expiredTime"
        val signKey = hmacSha1Hex(secretKey, keyTime)
        val headerKeys = headers.keys.sorted()
        val httpHeaders = headerKeys.joinToString("&") { k ->
            "${k.lowercase()}=${encodeURIComponent(headers.getValue(k))}"
        }
        val httpString = "${method.lowercase()}\n$pathname\n\n$httpHeaders\n"
        val stringToSign = "sha1\n$keyTime\n${sha1Hex(httpString)}\n"
        val signature = hmacSha1Hex(signKey, stringToSign)
        val headerList = headerKeys.joinToString(";") { it.lowercase() }
        return listOf(
            "q-sign-algorithm=sha1",
            "q-ak=$secretId",
            "q-sign-time=$keyTime",
            "q-key-time=$keyTime",
            "q-header-list=$headerList",
            "q-url-param-list=",
            "q-signature=$signature",
        ).joinToString("&")
    }

    private fun hmacSha1Hex(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun sha1Hex(data: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(data.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** 与 JS encodeURIComponent 行为一致，用于 COS 签名 header 值编码。 */
    private fun encodeURIComponent(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when {
                c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
                    c in "_.-!~*'()" -> sb.append(c)
                else -> {
                    for (b in c.toString().toByteArray(Charsets.UTF_8)) {
                        sb.append('%')
                        sb.append("%02X".format(b.toInt() and 0xFF))
                    }
                }
            }
        }
        return sb.toString()
    }

    companion object {
        private const val TAG = "ImaUploader"
        private const val BASE_URL = "https://ima.qq.com"

        @Volatile
        private var instance: ImaUploader? = null

        /** 获取单例，确保 Service / ViewModel 共享同一上传作用域与配置。 */
        fun get(context: Context): ImaUploader =
            instance ?: synchronized(this) {
                instance ?: ImaUploader(
                    context.applicationContext,
                    ImaSettings.get(context.applicationContext),
                ).also { instance = it }
            }
    }
}
