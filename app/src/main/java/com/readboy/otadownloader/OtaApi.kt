package com.readboy.otadownloader

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/** OTA 业务异常（可读错误信息直接面向用户） */
class OtaException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 查询到的升级包信息 */
data class OtaPackage(
    val version: String,
    val url: String,
    val md5: String,
    val size: Long,
    val description: String,
    val force: Boolean,
    val name: String,
    val remoteId: Int,
    val source: String,
    val rawResponse: String,
    val xmlUrl: String? = null,
    val rawXml: String? = null
) {
    fun prettySize(): String = when {
        size <= 0L -> "未知"
        size >= 1024L * 1024 * 1024 -> String.format("%.2f GB", size / 1024.0 / 1024 / 1024)
        size >= 1024L * 1024 -> String.format("%.1f MB", size / 1024.0 / 1024)
        size >= 1024L -> String.format("%.1f KB", size / 1024.0)
        else -> "$size B"
    }
}

/** 单次请求的结果（探测过程记录，供界面/日志展示） */
data class QueryAttempt(
    val index: Int,
    val label: String,
    val status: String,
    val success: Boolean,
    val detail: String = ""
)

/** 一轮查询的总体结果 */
data class QueryOutcome(
    val pkg: OtaPackage?,
    val attempts: List<QueryAttempt>,
    val matchedLabel: String?,
    val usedParams: Map<String, String>?,
    val errorMessage: String?
) {
    val success: Boolean get() = pkg != null
}

/**
 * 读书郎 OTA 接口客户端
 *
 * 逆向自 com.dream.ota.update（DreamUpdate.apk）：
 *  - 接口：POST http://ota.readboy.com/update.php （HTTP 明文，无签名无 token）
 *  - 响应形态一：{"data": {packageUrl, md5, size, version, force, description, ...}}
 *  - 响应形态二：{"url": "http://.../update.xml"} → 再下载 XML 解析
 *      XML 根节点属性：command（应为 update_with_inc_ota）、name、force
 *      XML 子节点：url / md5 / description / country / size / version
 *  - 服务器按 fingerprint 决定下发增量包还是完整包（增量要求基线指纹精确匹配）
 *  - 服务器按 (model, board, android, chipset) 匹配机型库，不匹配时返回
 *      {"status":"model not found"}；匹配但无可下发版本时返回
 *      {"status":"no update available"}
 */
object OtaApi {

    private const val TAG = "OtaApi"
    private const val ENDPOINT = "http://ota.readboy.com/update.php"
    private const val STATUS_MODEL_NOT_FOUND = "model not found"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * 单次查询（不探测）
     * @param channel 通道：0=正式 1=测试 2=公测（服务器参数名是 debug）
     * @param fingerprintOverride 自定义指纹（null 表示使用真实指纹）
     * @param overrides 参数覆盖（手动指定 chipset/display/model 等）
     */
    suspend fun query(
        context: Context,
        channel: Int,
        fingerprintOverride: String?,
        overrides: Map<String, String> = emptyMap()
    ): OtaPackage = withContext(Dispatchers.IO) {
        val info = DeviceInfoCollector.collect(context)
        val fingerprint = fingerprintOverride?.takeIf { it.isNotBlank() } ?: info.fingerprint
        val params = DeviceInfoCollector.buildParams(context, info, channel, fingerprint, overrides)
        post(params)
    }

    /**
     * 带自动探测的查询：先用官方默认参数请求，若服务器判定机型不匹配/无可下发版本，
     * 则依次尝试候选参数组合（chipset/display/hard 等），命中即返回。
     * @param onAttempt 每次尝试后的回调（在 IO 线程，用于实时更新界面/日志）
     */
    suspend fun queryWithProbe(
        context: Context,
        channel: Int,
        fingerprintOverride: String?,
        overrides: Map<String, String> = emptyMap(),
        maxAttempts: Int = 12,
        onAttempt: (QueryAttempt) -> Unit = {}
    ): QueryOutcome = withContext(Dispatchers.IO) {
        val info = DeviceInfoCollector.collect(context)
        val fingerprint = fingerprintOverride?.takeIf { it.isNotBlank() } ?: info.fingerprint

        val attempts = mutableListOf<QueryAttempt>()
        var lastError: String? = null

        // 候选序列：官方默认参数 + 覆盖项 + 探测项
        val base = DeviceInfoCollector.buildParams(context, info, channel, fingerprint, overrides)
        val candidates = mutableListOf<Pair<String, Map<String, String>>>()
        candidates.add("官方默认参数" to emptyMap())
        DeviceInfoCollector.candidates(info).forEach { candidates.add(it) }

        var index = 0
        for ((label, extra) in candidates) {
            if (index >= maxAttempts) break
            val params = LinkedHashMap(base)
            extra.forEach { (k, v) -> if (k in params) params[k] = v }
            if (attempts.any { it.detail == fingerprintOf(params) }) continue
            index++

            val result = runCatching { post(params) }
            result.onSuccess { pkg ->
                attempts.add(
                    QueryAttempt(
                        index = index,
                        label = label,
                        status = "成功",
                        success = true,
                        detail = fingerprintOf(params)
                    )
                )
                onAttempt(attempts.last())
                AppLogger.i(TAG, "探测命中：$label（第 $index 次）")
                return@withContext QueryOutcome(pkg, attempts, label, params, null)
            }.onFailure { e ->
                val msg = e.message.orEmpty()
                lastError = msg
                val status = when {
                    msg.contains(STATUS_MODEL_NOT_FOUND, ignoreCase = true) -> "机型不匹配"
                    msg.contains("no update available", ignoreCase = true) -> "该机型无此版本"
                    else -> "失败"
                }
                attempts.add(
                    QueryAttempt(
                        index = index,
                        label = label,
                        status = status,
                        success = false,
                        detail = fingerprintOf(params)
                    )
                )
                onAttempt(attempts.last())
                AppLogger.w(TAG, "探测第 $index 次（$label）失败：$status")
            }
        }

        QueryOutcome(null, attempts, null, null, lastError)
    }

    /** 探测去重：用可变量拼接的签名 */
    private fun fingerprintOf(params: Map<String, String>): String =
        "chipset=${params["chipset"]}" + "|display=${params["display"]}" + "|hard=${params["hard"]}" +
            "|model=${params["model"]}"

    /** 发送一次请求并解析 */
    private fun post(params: LinkedHashMap<String, String>): OtaPackage {
        val formBuilder = FormBody.Builder()
        params.forEach { (k, v) -> formBuilder.add(k, v) }

        AppLogger.i(TAG, "请求 $ENDPOINT")
        AppLogger.d(TAG, "请求参数:\n" + params.entries.joinToString("\n") { "  ${it.key} = ${it.value}" })

        val request = Request.Builder()
            .url(ENDPOINT)
            .post(formBuilder.build())
            .header("User-Agent", "ZaralynOTA/${BuildConfig.VERSION_NAME}")
            .build()

        val raw = try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                AppLogger.d(TAG, "HTTP ${response.code}, 响应长度 ${text.length}")
                if (!response.isSuccessful) {
                    throw OtaException("服务器返回 HTTP ${response.code}")
                }
                if (text.isBlank()) {
                    throw OtaException("服务器返回空响应")
                }
                AppLogger.i(TAG, "原始响应: ${text.take(2000)}")
                text
            }
        } catch (e: OtaException) {
            throw e
        } catch (e: Exception) {
            AppLogger.caught(TAG, "请求 update.php", e)
            throw OtaException("网络请求失败: ${e.message}", e)
        }

        return parseResponse(raw)
    }

    /** 解析响应：JSON 直出 或 XML 配置地址；无包时抛 OtaException（含服务器 status 原文） */
    private fun parseResponse(raw: String): OtaPackage {
        val json = try {
            JSONObject(raw)
        } catch (e: Exception) {
            AppLogger.caught(TAG, "解析响应 JSON", e)
            throw OtaException("响应不是合法 JSON: ${raw.take(200)}", e)
        }

        // 形态一：data 对象直出包信息
        val data = json.optJSONObject("data")
        if (data != null && data.has("packageUrl")) {
            val url = data.optString("packageUrl").replace(" ", "%20")
            if (url.isBlank()) {
                throw OtaException("服务器返回的下载地址为空")
            }
            val pkg = OtaPackage(
                version = data.optString("version"),
                url = url,
                md5 = data.optString("md5"),
                size = data.optString("size").toLongOrNull() ?: 0L,
                description = data.optString("description"),
                force = data.optString("force", "false").equals("true", ignoreCase = true),
                name = data.optString("name"),
                remoteId = data.optInt("id", 0),
                source = "JSON data.packageUrl (status=${json.optString("status")})",
                rawResponse = raw,
                xmlUrl = json.optString("url").takeIf { it.isNotBlank() }
            )
            AppLogger.i(TAG, "查询成功(JSON): 状态=${json.optString("status")}, 版本=${pkg.version}, 大小=${pkg.prettySize()}")
            AppLogger.i(TAG, "包地址: ${pkg.url}")
            pkg.xmlUrl?.let { AppLogger.i(TAG, "配置文件: $it") }
            return pkg
        }

        // 形态二：url 指向 XML 配置
        val xmlUrl = json.optString("url")
        if (xmlUrl.isNotBlank()) {
            AppLogger.i(TAG, "响应返回 XML 配置地址: $xmlUrl")
            val cleanXmlUrl = xmlUrl.replace(" ", "").replace("\r", "").replace("\n", "")
            val xml = fetchXml(cleanXmlUrl)
            val pkg = parseXml(cleanXmlUrl, xml, raw)
            AppLogger.i(TAG, "查询成功(XML): 版本=${pkg.version}, 大小=${pkg.prettySize()}, 地址=${pkg.url}")
            return pkg
        }

        // 无更新：把服务器 status 原文带出来，便于用户判断（model not found / no update available）
        val status = json.optString("status")
        AppLogger.w(TAG, "服务器无可下发升级包，status=$status")
        val hint = when {
            status.contains("model not found", ignoreCase = true) ->
                "服务器机型库中未匹配到该机型（匹配键：model + board + android + chipset，实测结论）\n" +
                    "请保持「自动参数探测」开启后重试；若仍失败，可到「高级参数」手动指定 chipset。"
            status.contains("no update available", ignoreCase = true) ->
                "服务器已识别该机型（四元组匹配成功），但按当前 display 版本没有可下发的包。\n" +
                    "可尝试切换通道，或在「高级参数」里填入其他 display（当前版本号）。"
            status.contains("no firmware version param", ignoreCase = true) ->
                "服务器缺少 display 参数（当前版本号）。请在「高级参数」里手动填写 display 后重试。"
            else -> "服务器未返回可用升级包。"
        }
        throw OtaException("$hint\n服务器 status: ${status.ifBlank { raw.take(200) }}")
    }

    /** 下载并解析 XML 配置 */
    private fun fetchXml(url: String): String {
        val request = Request.Builder().url(url).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw OtaException("获取 XML 配置失败: HTTP ${response.code}")
                }
                if (text.isBlank()) {
                    throw OtaException("XML 配置内容为空")
                }
                AppLogger.d(TAG, "XML 内容: ${text.take(2000)}")
                text
            }
        } catch (e: OtaException) {
            throw e
        } catch (e: Exception) {
            AppLogger.caught(TAG, "下载 XML 配置", e)
            throw OtaException("获取 XML 配置失败: ${e.message}", e)
        }
    }

    /** 解析 XML：根节点属性 command/name/force，子节点 url/md5/description/size/version */
    private fun parseXml(xmlUrl: String, xml: String, rawResponse: String): OtaPackage {
        val root = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            }
            factory.newDocumentBuilder()
                .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
                .documentElement
        } catch (e: Exception) {
            AppLogger.caught(TAG, "解析 XML", e)
            throw OtaException("解析 XML 配置失败: ${e.message}", e)
        }

        val command = root.attribute("command")
        val name = root.attribute("name")
        val force = root.attribute("force").equals("true", ignoreCase = true)
        AppLogger.i(TAG, "XML 根节点: command=$command, name=$name, force=$force")
        if (command.isNotBlank() && command != "update_with_inc_ota") {
            AppLogger.w(TAG, "XML command 非 update_with_inc_ota（官方客户端会拒绝处理），仍尝试提取地址")
        }

        val url = child(root, "url").replace(" ", "%20")
        if (url.isBlank()) {
            throw OtaException("XML 配置中未找到升级包地址（url 节点为空）")
        }

        return OtaPackage(
            version = child(root, "version"),
            url = url,
            md5 = child(root, "md5"),
            size = child(root, "size").toLongOrNull() ?: 0L,
            description = child(root, "description"),
            force = force,
            name = name,
            remoteId = 0,
            source = "XML($command)",
            rawResponse = rawResponse,
            xmlUrl = xmlUrl,
            rawXml = xml
        )
    }

    private fun Element.attribute(name: String): String = getAttribute(name)?.trim().orEmpty()

    private fun child(root: Element, tag: String): String {
        val nodes = root.getElementsByTagName(tag)
        if (nodes.length == 0) return ""
        return nodes.item(0)?.textContent?.trim().orEmpty()
    }
}
