# NekoMaid 使用文档

本文档描述 NekoMaid 的全部功能、使用方法和配置项。适用于 `master`（Paper 26.2）版本。

## 1. 简介

NekoMaid 是一个 Bukkit/Paper 服务端 Web 管理插件。服务端通过内置 Netty HTTP 服务器（默认监听 `127.0.0.1:12334`）提供 Socket.IO 接口，浏览器前端面板通过 nginx TLS 反代连接。

### 访问方式

- 默认前端：`https://BA-Capple.github.io/NekoMaid-Next/?<连接地址>`
- 自托管前端：`https://<前端域名>/?<连接地址>`

`<连接地址>` 形如 `https://ddns.example.com:12333/NekoMaid?<token>`，由插件自动生成；游戏内或控制台执行 `/nekomaid`（别名 `/nm`）即可拿到管理 URL。

## 2. 命令参考

所有子命令均通过 `/nekomaid` 或 `/nm` 使用。

### 2.1 `/nm`（不带参数）

发送当前主 token 的管理 URL。

### 2.2 `/nm help`

列出所有子命令用法。

### 2.3 `/nm temp`

生成一个 60 分钟有效的临时 token（单因子，免 TOTP）。适合临时给他人或自己应急使用。

### 2.4 `/nm reload`

重载 `config.yml`（`hostname`、`customAddress`、`debug`、tokens 等）。注意：`port` 修改需要重启服务器才生效。

### 2.5 `/nm diagnostic`

诊断当前 `hostname` 配置是否能成功握手，输出诊断结果。

### 2.6 `/nm invalidate`

清空临时 token 缓存（使已发出的 temp token 立即失效）。

### 2.7 `/nm 2fa status|setup|enable <code>|disable`

管理主 token 的 TOTP 两步验证。

> 权限：`reload`、`2fa`、`token`、`diagnostic` 这些会读取/修改 NekoMaid 配置的子命令，仅控制台或主 token 绑定的玩家可执行；`/nm`、`/nm block`、`/nm entity`、`/nm temp`、`/nm help` 不受影响。

- `status`：查看主 token 是否已配置 2FA。
- `setup`：生成新的 TOTP secret，并保存二维码到 `plugins/NekoMaid/2fa-qr.png`。
- `enable <code>`：用验证器中的 6 位数字确认并启用。
- `disable`：清空主 token 的 secret。

> 安全模型是 fail-closed：主 token 没有 secret 时无法连接面板，直到重新 `setup`。

### 2.8 `/nm token list|create <name> [player]|revoke <name>|bind <player>`

管理副 token：

- `list`：列出所有 token（token 值打码，不显示 TOTP secret）。
- `create <name> [player]`：创建副 token，输出 token、TOTP secret 和管理 URL；可选绑定玩家名（该玩家 `/op` 时复用该副 token）。
- `revoke <name>`：删除副 token。
- `bind <player>`：把主 token 绑定到玩家名。之后对这名玩家执行 `/op` 不会自动创建副 token，也不会把全权限主 token 链接发到聊天。

### 2.9 `/nm block [<world> <x> <y> <z>]`

生成方块编辑链接。

- 不带参数：瞄准玩家视线方向的方块（玩家执行）。
- 带 4 个参数：指定世界和坐标。

### 2.10 `/nm entity [<uuid>]`

生成实体编辑链接。

- 不带参数：瞄准玩家视线方向的实体（玩家执行）。
- 带 UUID：指定实体。

## 3. 前端页面

### 3.1 Dashboard（仪表盘）

- 显示服务器版本、TPS/MSPT、在线玩家、内存、运行时长、版本更新状态。
- 可点击“检查更新”。
- 主 token 或拥有 `players` 权限的副 token 可以踢出在线玩家。

### 3.2 Terminal（终端）

- 显示服务端控制台日志（最近 100 条）。
- 可以执行服务端命令，支持 Tab 补全。
- 执行 `/op`、`/deop` 时与游戏内一样自动创建/撤销该玩家的副 token。
- 需要 `terminal` 权限（主 token 默认拥有；副 token 默认禁用）。

### 3.3 Files（文件）

- 浏览/编辑服务端根目录下的文件（最大编辑 4MB）。
- 支持新建目录、重命名、复制、删除、下载、上传、压缩/解压（zip/tar/jar/ar/cpio）。
- 副 token 即使被授予 `files`，也不能访问 `plugins/NekoMaid` 和 `plugins/LuckPerms` 目录；例外：主 token 绑定的玩家（其 token 的 `player` 与主 token 的 `player` 一致）可以访问 `plugins/NekoMaid` 配置目录，但仍不能访问 `plugins/LuckPerms`。
- 需要 `files` 权限。

### 3.4 Plugins（插件）

- 列出所有插件（启用/停用/未加载）。
- 支持启用、停用（`.jar.disabled`）、删除已停用插件。
- 集成 PlugMan / ServerUtils 时能力更强；未集成时部分操作不可用。
- 需要 `plugins` 权限。

### 3.5 Worlds（世界）

- 查看世界列表和规则。
- 切换天气（晴→雨→雷雨）、设置难度、切换 PvP、修改游戏规则、保存世界、修改视距。
- 安装了 Multiverse-Core 4.x 时可修改世界别名（`worlds:set`）。
- 需要 `worlds` 权限。

### 3.6 Block Editor（方块编辑器）

- `/nm block` 生成的链接会打开对应方块。
- 查看/编辑方块类型、BlockData、NBT 和容器内物品。
- 查看需要 `block` 权限；修改类型/保存 NBT/改物品需要 `editors` 权限。

### 3.7 Entity Editor（实体编辑器）

- `/nm entity` 生成的链接会打开对应实体。
- 查看/编辑实体 NBT、自定义名称、可见性、发光、重力、无敌、静音；有背包的实体可编辑背包。
- 查看需要 `entity` 权限；保存/修改需要 `editors` 权限。

### 3.8 Profiler（性能分析）

- 摘要（TPS/MSPT/CPU/内存/GC）。
- Timings（Paper Timings，若可用）。
- 插件事件耗时统计。
- 实体/方块实体分布。
- Java 堆直方图和线程转储。
- 需要 `profiler` 权限。

### 3.9 Scheduler（计划任务）

- 创建/编辑/删除 cron 计划任务。
- 任务内容可以是以 `/` 开头的命令，或直接发送的聊天文本。
- 支持 PlaceholderAPI 占位符（安装时）。
- 查看需要 `scheduler` 权限；修改/执行需要 `terminal` 权限。

### 3.10 PlayerList（玩家列表）

- 查看离线/在线玩家、白名单、封禁列表。
- 查询玩家统计（游戏时长、击杀、死亡等）。
- 封禁/解封、添加/移除白名单需要 `players` 权限；查看需要 `playerList` 权限。

### 3.11 Inventory（玩家背包）

- 查看/编辑在线玩家背包和末影箱。
- 安装 OpenInv 或 InvSeePlusPlus 时可查看/编辑离线玩家背包。
- 需要 `inventory` 权限。

### 3.12 TokenManage（令牌管理）

- 主 token（以及主 token 绑定的玩家对应的 token）可查看全部副 token，调整每个副 token 的权限白名单和 `allowNo2fa` 开关。
- 其他副 token 打开本页时只能看到自己的 token 信息（token 值、绑定玩家、权限列表、allowNo2fa），不能查看或修改其他 token。

### 3.13 Config（服务器配置）

- 修改 `maxPlayers`、`spawnRadius`、`motd`、白名单开关。
- 查看 JVM/CPU 信息。
- 前端主题色/深色模式/历史服务器是浏览器本地配置。
- 需要 `config` 权限。

### 3.14 Vault（经济/权限，可选）

- 仅当服务器安装 Vault 且存在经济/权限/Chat provider 时出现。
- 查看玩家余额、修改余额、查看/修改玩家和组的权限与前后缀。
- 需要 `vault` 权限。

## 4. 配置参考（config.yml）

| 键 | 说明 |
| --- | --- |
| `tokens` | token 列表。每项 `{name, token, secret, permissions?, primary?, allowNo2fa?, player?}` |
| `hostname` | 公网连接地址，**必须带端口**，如 `ddns.example.com:12333` |
| `customAddress` | 自定义管理地址模板。可用占位符 `{hostname}`（URL 编码后的 socket 地址）和 `{token}`。为空时使用默认 GitHub Pages 前端 |
| `port` | 内置 HTTP 服务器监听端口（默认 `12334`）。**修改后需重启** |
| `static-path` | 静态资源目录（相对于插件数据目录） |
| `gzip` | HTTP 响应是否 gzip（默认 `true`） |
| `skin-url` | 自定义皮肤获取地址（空则用默认） |
| `head-url` | 自定义玩家头颅获取地址（空则用默认） |
| `geolite2-eula` | 是否同意 MaxMind GeoIP EULA |
| `geolite2-license-key` | MaxMind GeoIP 许可证 key；未配置则跳过 GeoIP 下载 |
| `baidu-map-license-key` | 百度地图 AK（玩家位置地图）；未配置则不加载地图 |
| `logger.maxLevel` / `logger.minLevel` | 终端日志级别范围 |
| `debug` | 是否打印调试堆栈（排查问题用） |

## 5. Token 与权限模型

- **主 token**（`primary: true`）拥有全部权限，并可管理副 token。主 token 必须配置 TOTP 才能连接（fail-closed）。
- **副 token** 按 `permissions` 白名单授权。可用权限：
  - 默认只读/低危：`dashboard`, `playerList`, `players`, `worlds`, `profiler`, `scheduler`, `entity`, `block`
  - 高危（默认禁用）：`terminal`, `plugins`, `files`, `config`, `editors`, `vault`, `inventory`
- 副 token 默认必须配置 TOTP；设置 `allowNo2fa: true` 可免 TOTP 连接。
- **临时 token**（`/nm temp`）有效 60 分钟，单因子免 TOTP，权限等同默认副 token。
- OTP 暴力防护：同一 token+IP 连续失败 5 次锁定 5 分钟。
- 会话：验证通过后签发 8 小时 session（绑定 IP），浏览器刷新/重连免输 OTP。

## 6. 常见问题

| 问题 | 处理 |
| --- | --- |
| 前端打不开 | 确认 `hostname` 带端口、页面协议与 socket 协议一致（https→wss 反代） |
| 连接被拒 / 显示 `!` | 主 token 未配置 2FA；执行 `/nm 2fa setup` 后扫码并 `enable <code>` |
| 方块链接空白 | 重新部署前端 dist（`block/:world/:x/:y/:z` 路由修复） |
| 实体/方块页操作失败 | 副 token 需授予 `editors` 权限才能保存/修改 |
| 修改 `port` 无效 | 需要重启服务端 |
| 副 token 看不到文件/终端/插件页 | 这些页面需要相应高危权限；页面会显示但操作被拒绝或为空 |

## 7. 部署与运维

详见 `docs/DEPLOYMENT.md`（服务器拓扑、nginx 反代、前端部署、替换 jar 流程）。
