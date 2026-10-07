## Zaralyn OTA 固件下载器

按当前机型向读书郎 OTA 服务器查询固件包地址，并支持直接下载（Material Design 3）。

**支持系统：Android 5.0 (API 21) ~ Android 14 (API 34)，含老机型 Android 7 (API 24/25)**

### 功能

- **查询固件地址**：POST `ota.readboy.com/update.php`（无鉴权、HTTP 明文），兼容 JSON / XML 两种响应形态
- **机型匹配**：服务器按 `(model, board, android, chipset)` 四元组精确匹配；`chipset` 取设备自身 `ro.build.chipset`
- **自动参数探测**（默认开）：默认参数不匹配时，自动尝试多种 `chipset`/`display`/`hard` 取值，命中即用并在界面显示「参数组合命中：…」
- **高级参数**：可手动覆盖 `model`/`board`/`android`/`chipset`/`display`/`hard`/`serial`，持久化保存、可一键恢复默认
- **系统属性**：查看/复制 `getprop` 全量属性（机型库不匹配时现场取证）
- **通道选择**：正式 / 测试 / 公测（对应服务器参数 `debug` = 0 / 1 / 2）
- **直接下载**：流式下载 + 实时进度/速度 + 边下边算 MD5 校验 + 可取消
- **日志系统**：Logcat / 内存环 / 按天滚动文件（自动清理），崩溃自动落盘
- **全链路错误捕获**：网络、HTTP、JSON/XML、IO，界面可直接查看堆栈与日志

### 老机型适配（本版重点）

- 属性读取**三通道回退**：`SystemProperties` 反射 → `getprop` → `/system/build.prop`（老 ROM 常限制其中一两路）
- `display` 回退链：`ro.fota.version` → `ro.build.display.ota` → `ro.build.display.id` → `Build.DISPLAY`
- 开启 **core library desugaring**，API 21~23 不再因 Java 8 默认方法报 `NoSuchMethodError`
- launcher 图标提供 5 档 PNG + API 26+ 自适应图标，老 Launcher 不再可能白图标
- 新增 **「兼容性自检」**：系统版本 / 属性读取三通道 / `chipset`·`board`·版本属性 / OTA 服务器连通 / 下载目录可写 / 剩余空间 / 日志目录 / FileProvider，可一键复制报告

### 安装说明

1. 下载 APK 并安装（Android 5.0+）
2. 首次启动无需额外权限（仅联网）
3. 选通道 → 「查询更新」→「下载固件」

### 说明

- 服务器是在 `display`（当前版本号）基础上按梯度下发**增量包**（实测：伪造 `fingerprint` 不会得到完整包；能否拿到全量包取决于服务端机型库条目）
- 若提示 `model not found`：先看「兼容性自检」，再用「高级参数」手动填 `chipset`（或把自检报告/日志反馈给开发者）
- 逆向参考：DreamUpdate 2.1.0（`com.dream.ota.update`）
- 固件保存于应用私有目录 `Android/data/com.readboy.otadownloader/files/Download/`
- 仅供设备所有者对自己的设备做固件存档使用
