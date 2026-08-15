# NekoMaidRemaster

用网页管理 Minecraft 服务器的 Paper 插件（NekoMaid 的重构分支）。

基于 [NekoMaid](https://github.com/neko-craft/NekoMaid)（AGPL-3.0）重构，主要变化：

- **移除 Uniporter 依赖**：内置独立 Netty HTTP 服务器（`NekoMaidHttpServer`），不再需要任何前置插件
- **兼容 PaperMC 26.2**：移除 NMS 反射，适配新 API
- **多 token + 每 token 独立 TOTP 二步验证**：`config.yml` 的 `tokens` 列表，主 token 全权限，副 token 按白名单权限受限
- **前端独立部署**：web 面板由 Vite 构建为静态站点，可单独托管（或由插件 `static-path` 服务）

## Requirements

- Paper 26.2+（需 Java 25 运行）
- 无必需前置插件；以下为可选集成（`softdepend`）：Vault、NBTAPI、OpenInv、InvSeePlusPlus、PlugMan、PlaceholderAPI、Multiverse-Core

## Usage

1. 构建：`./gradlew shadowJar --no-daemon`，产物为 `build/libs/NekoMaid-1.0-SNAPSHOT.jar`
2. 将 jar 放入服务器 `plugins` 目录并重启服务器
3. 编辑 `config.yml`：设置 `hostname`（公网连接地址，**必须带端口**）与 `tokens`
4. 控制台执行 `/nm`（或 `/nekomaid`）查看管理地址

## Commands

- **/nekomaid help**: 帮助。
- **/nekomaid temp**: 生成临时连接地址（15 分钟过期，独立 TOTP secret + 二维码）。
- **/nekomaid reload**: 重载配置。
- **/nekomaid diagnostic**: 诊断 `hostname` 配置。
- **/nekomaid invalidate**: 撤销所有已生成的临时地址。
- **/nekomaid token create|list|revoke**: 管理副令牌（主令牌可用）。
- **/nekomaid 2fa setup|enable|disable**: 管理二步验证（主令牌可用）。
- **/nekomaid block (WORLD) (X) (Y) (Z)**: 在网页中编辑面对的方块。
- **/nekomaid entity (UUID)**: 在网页中编辑面对的实体。

## Configures

```yaml
# tokens 列表：primary: true 的条目为主令牌（全权限，可管理其它令牌）。
# 副令牌受 permissions 白名单限制；每条目有自己的 TOTP secret。
# 副令牌可设 allowNo2fa: true 免除二步验证（默认强制 2fa）。
tokens: []

hostname: 127.0.0.1        # 公网连接地址（必须带端口，如 ddns.example.com:12333）
customAddress: ''          # 自定义管理地址模板
port: 12334                # HTTP 监听端口（建议仅本机监听 + 反代 TLS 终结）
static-path: static        # 静态资源目录（相对插件数据目录或绝对路径）
gzip: true                 # HTTP 响应 gzip 压缩

skin-url: ''               # 自定义皮肤 URL（{} 替换为用户名或 UUID）
head-url: ''               # 自定义头像 URL（{} 替换为用户名或 UUID）

geolite2-eula: false       # 是否同意 MaxMind GeoIP2 EULA
baidu-map-license-key: ''  # 百度地图密钥（仅前端部署时使用，禁止硬编码在前端）

logger:
  maxLevel: 'INFO'
  minLevel: 'OFF'

debug: false               # 调试模式（打印异常栈）
```

## Permissions

- **neko.maid.use**: 允许使用 `/nekomaid` 命令（默认 op）。
- **neko.maid.admin**: 管理命令（reload/diagnostic/invalidate/token，默认 op）。
- **neko.maid.2fa**: 二步验证管理（默认 op）。

副 token 的 `permissions` 白名单：`dashboard`/`playerList`/`players`/`worlds`/`profiler`/`scheduler`/`entity`/`block`（默认开，只读 + 玩家管理）与 `terminal`/`plugins`/`files`/`config`/`editors`/`vault`/`inventory`（默认禁，高危）。

## Security

- 所有 token 强制 2fa（fail-closed）：secret 为空或 OTP 错误一律拒绝；OTP 暴力尝试 5 次锁 5 分钟。
- 主 token = 服务器管理员（Terminal/Plugins 为设计内能力）；副 token 默认无高危能力，可经面板「令牌管理」页调整。
- FilesManager 路径穿越防护（normalize + 软链拒绝 + realpath 校验 + zip slip 防护）。

## For developers

- 后端：Java 25 toolchain（Gradle 自动下载）、shadowJar relocate 依赖至 `cn.apisium.nekomaid.libs.*`
- 前端：`npm install --legacy-peer-deps` → `npm run build`（产物 `dist/`，`base: './'` 相对路径）

## Screenshot

![0](screenshots/0.png)
![1](screenshots/1.png)
![2](screenshots/2.png)
![3](screenshots/3.png)
![4](screenshots/4.png)
![5](screenshots/5.png)
![6](screenshots/6.png)

## License

[AGPL-3.0](./LICENSE)

基于 [NekoMaid](https://github.com/neko-craft/NekoMaid)（作者 Shirasawa）重构。
