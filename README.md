# Zaralyn OTA

读书郎学习平板**固件包下载工具**（Material Design 3 / Kotlin / Android）。

按当前机型的设备参数向读书郎 OTA 服务器查询固件地址，支持**完整包模式**并可直接下载、校验 MD5。

> 逆向参考对象：`DreamUpdate.apk`（com.dream.ota.update，系统更新应用）
> 详细接口文档见：`ParentManager-Network-API-Request-Response-Reference.md` 第十一章

---

## 功能

| 功能 | 说明 |
|------|------|
| 设备信息采集 | 与官方一致：优先系统属性 `ro.*`，回退 `android.os.Build`；serial 多来源回退（`ro.serialno` / Settings.Global 读书郎 SN / `Build.getSerial` / `/proc/cpuinfo`） |
| 固件查询 | `POST http://ota.readboy.com/update.php`，解析 JSON 直出与 XML 配置两种响应 |
| 完整包模式 | 发送服务器无法匹配的伪造指纹 → 服务端回退完整包（增量包要求基线指纹精确匹配） |
| 通道选择 | 正式(0) / 测试(1) / 公测(2) |
| 直接下载 | OkHttp 流式下载，实时进度/速度，边下边算 MD5 并与服务器比对 |
| 日志系统 | Logcat + 内存环形缓冲 + 按天滚动文件（自动清理），崩溃写入 `last_crash.txt` |
| 错误捕捉 | 网络/HTTP/JSON/XML/IO 全链路捕获，界面可查看堆栈与日志 |

## 逆向得到的接口约定

### 请求

```
POST http://ota.readboy.com/update.php
Content-Type: application/x-www-form-urlencoded
```

参数（与官方应用 `UpdaterInfo` 一一对应）：

| 参数 | 来源 |
|------|------|
| `updating_apk_version` | 本应用版本名 |
| `model` | `ro.product.model` |
| `brand` | `ro.product.brand` |
| `name` | `ro.product.name` |
| `device` | `ro.product.device` |
| `board` | `ro.product.board` |
| `mac` | WiFi MAC（取不到为空） |
| `firmware` | `ro.product.firmware` |
| `android` | `ro.build.version.release` |
| `time` | `ro.build.date.utc` |
| `builder` | `ro.build.user` |
| `fingerprint` | `ro.build.fingerprint`（**决定增量/完整包**） |
| `display` | `ro.fota.version` |
| `hard` | `ro.build.version.hard` |
| `serial` | 多来源回退 |
| `chipset` | `/sys/devices/soc0/soc_id` 或 `ro.board.platform` |
| `id` | 通道：0 正式 / 1 测试 / 2 公测 |
| `debug` / `is_log` / `ip` | 遥测字段 |
| `uid` / `username` / `realname` | 账号（未登录为空） |

> ⚠️ 接口**无签名、无 token、无设备绑定校验**，HTTP 明文。

### 响应（两种形态）

**形态 A：JSON 直出**

```json
{ "data": { "id": 123, "md5": "...", "packageUrl": "http://.../ota.zip",
            "description": "...", "force": "false", "size": "1000000000",
            "name": "...", "version": "1.2.3" } }
```

**形态 B：XML 配置地址**

```json
{ "url": "http://ota.readboy.com/xxx/update.xml" }
```

```xml
<root command="update_with_inc_ota" name="固件名" force="true">
  <url>http://.../ota.zip</url>
  <md5>...</md5>
  <description>...</description>
  <country>...</country>
  <size>1000000000</size>
  <version>1.2.3</version>
</root>
```

## 构建

推送到 `main` 由 GitHub Actions 自动构建（debug APK + 签名 release APK），无需本地 Android SDK。

## 日志

- 目录：`Android/data/com.readboy.otadownloader/files/logs/`
- 文件：`ota-yyyyMMdd.log`，保留最近 10 个，单文件超 2MB 自动归档
- 崩溃记录：`Android/data/com.readboy.otadownloader/files/last_crash.txt`（下次启动自动加载进日志）
- 界面右上角「日志」可查看/复制/分享/清空

## 免责声明

仅供设备所有者对自己的设备做固件存档与离线刷机使用。请遵守当地法律法规与厂商服务条款，作者不对任何滥用行为负责。

## 版本

- v1.0：首个版本（查询完整包地址 + 直接下载 + 日志系统）
