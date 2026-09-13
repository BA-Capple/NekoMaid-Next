# NekoMaid-Next

用网页管理 Minecraft 服务器的 Paper 插件（NekoMaid 的重构分支）。

基于 [NekoMaid](https://github.com/neko-craft/NekoMaid)（AGPL-3.0）重构，主要变化：

- **开箱即用**：把 `NekoMaid.jar` 丢进 `plugins/` 就能用 —— 面板前端内置在 jar 里（首次启动自动解包），未配置证书时自动生成自签证书并以 HTTPS 提供服务。**不需要 nginx、不需要证书、不需要单独部署前端、不需要改任何配置。**
- **移除 Uniporter 依赖**：内置独立 Netty HTTP 服务器（`NekoMaidHttpServer`），不再需要任何前置插件。
- **单端口复用（可选）**：可让面板与 Minecraft **共用同一个对外端口**，按 TCP 首包嗅探分流（浏览器走 HTTPS，玩家走 Minecraft，对玩家完全透明）。
- **兼容 PaperMC 1.21.11**：移除 NMS 反射，适配新 API。
- **多 token + 每 token 独立 TOTP 二步验证**：主 token 全权限，副 token 按白名单权限受限。
- **版本线**：本分支面向 **Paper 1.21.11**（最低 Java 21）；Paper 26.2（Java 25）版本见 `master` 分支。

## Requirements

- Paper 1.21.11（最低 Java 21；本分支用 JDK 25 以 `--release 21` 编译，产物 class 版本 65，兼容 Java 21 运行时）
- 无必需前置插件；以下为可选集成（`softdepend`）：Vault、NBTAPI、OpenInv、InvSeePlusPlus、PlugMan、PlaceholderAPI、Multiverse-Core

## Quick start

1. 下载 `NekoMaid.jar`，放进服务器 `plugins/` 目录。
2. 启动服务器。控制台会打印面板地址：

   ```
   [NekoMaid] Unpacked 2956 bundled web panel files into plugins/NekoMaid/static
   [NekoMaid] Web panel listening on https://0.0.0.0:8443
   ```

3. 控制台执行 `/nm`，用它输出的**完整链接**打开面板。链接形如
   `https://<地址>:8443/?<地址>%3A8443%2FNekoMaid%3F<token>` —— **末尾那个 `?` 参数必须带着**，
   否则前端会停在「连接到服务器 / 请输入服务器地址」界面（看起来就像面板没功能）。
4. 首次访问会提示证书不受信任（自签证书），点一次「继续访问」即可；证书保存在
   `plugins/NekoMaid/self-signed-cert.pem`，之后重启不再变化。
5. 首次连接会引导完成二步验证（TOTP）绑定。

> 直接打开裸地址（`https://<服务器IP>:8443/`）会自动跳转到带上参数的地址，但**没有令牌时前端仍要求手动输入地址与令牌**。日常请使用第 3 步的完整链接；`/nm temp` 可生成 60 分钟有效、免二步验证的临时链接。

想用真实证书（去掉浏览器警告）：把 PEM 证书链和私钥路径填进 `tls.certificate` / `tls.private-key`，私钥支持 PKCS#8（`PRIVATE KEY`）、SEC1（`EC PRIVATE KEY`）、PKCS#1（`RSA PRIVATE KEY`）三种格式 —— openssl / nginx / Let's Encrypt 产出的都能直接读。

## 三种部署方式

| 方式 | 适用 | 配置要点 |
|---|---|---|
| **直接暴露**（默认） | 单机、想立刻用 | 默认即为此模式：`port: 8443`、`bind-address: 0.0.0.0`、`tls.enabled: true`（无证书则自签） |
| **反代终结 TLS** | 已有 nginx / 有真证书 | `bind-address: 127.0.0.1`、`tls.enabled: false`、`trust-proxy-headers: true`，由 nginx 反代到该端口 |
| **与 MC 共用端口** | 只想对外暴露一个端口 | 见下方「单端口复用」 |

### 单端口复用

让面板和 Minecraft 共用同一个对外端口（例如 25565），玩家仍连 `你的域名:25565`，浏览器访问 `https://你的域名:25565/`：

1. 让 Minecraft 让出该端口（`server.properties`）：`server-port=25566`、`server-ip=127.0.0.1`
2. （推荐）让 Minecraft 侧接受 HAProxy PROXY 协议，以保留玩家真实 IP：`config/paper-global.yml` 里 `proxies.proxy-protocol: true`
3. `config.yml` 打开复用：

```yaml
port-share:
  enabled: true
  port: 25565              # 对外的复用端口
  bind-address: '::'       # '::' 双栈；只用 IPv4 可写 '0.0.0.0'
  minecraft-host: 127.0.0.1
  minecraft-port: 25566    # 与 server-port 一致
  proxy-protocol: true     # 必须与 paper-global.yml 的取值一致，否则玩家连不上
  allow-plain-http: false  # 公网端口请保持 false（否则令牌/OTP 明文传输）
  tls:
    enabled: true
    certificate: /etc/nginx/certs/fullchain.pem
    private-key: /etc/nginx/certs/key.pem
```

访问地址相应改为 `https://你的域名:25565/?<令牌>`（同时更新 `hostname` 与 `customAddress`）。

> 分流依据（`ProtocolDetector`）：TLS 记录（`0x16 0x03`）→ 面板；Minecraft handshake（VarInt 长度后紧跟 packet id `0x00`）→ 转发给真实 MC 端口；**其余一律视为 Minecraft**，未知流量不会被 Web 侧吞掉。

## Commands

- **/nekomaid help**: 帮助。
- **/nekomaid temp**: 生成临时连接地址（60 分钟有效，**无需两步验证**）。
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

hostname: 127.0.0.1        # 公网连接地址（如 ddns.example.com:25565）
customAddress: ''          # 自定义管理地址模板
port: 8443                 # 面板端口（默认 8443；反代模式常改回 12334）
bind-address: 0.0.0.0      # 反代模式建议 127.0.0.1
trust-proxy-headers: false # 仅在"只有你控制的反代能进来"时设 true；直连暴露时必须 false
tls:
  enabled: true            # 面板自身终结 TLS；无证书时自动生成自签证书
  certificate: ''          # PEM 证书链（相对路径基于插件目录）
  private-key: ''          # PEM 私钥（PKCS#8 / SEC1 / PKCS#1 均可）
static-path: static        # 静态资源目录（默认由 jar 内置前端自动解包）
gzip: true                 # HTTP 响应 gzip 压缩

port-share:                # 与 Minecraft 共用端口（见上文）
  enabled: false
  port: 25565
  bind-address: '0.0.0.0'
  minecraft-host: 127.0.0.1
  minecraft-port: 25566
  proxy-protocol: false
  allow-plain-http: false
  tls:
    enabled: true
    certificate: ''
    private-key: ''

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

- 主/副 token 强制 2fa（fail-closed）：secret 为空或 OTP 错误一律拒绝；OTP 暴力尝试 5 次锁 5 分钟。`/nekomaid temp` 生成的临时 token（60 分钟有效）为单因子，无需两步验证。
- 主 token = 服务器管理员（Terminal/Plugins 为设计内能力）；副 token 默认无高危能力，可经面板「令牌管理」页调整。
- FilesManager 路径穿越防护（normalize + 软链拒绝 + realpath 校验 + zip slip 防护）。
- **来源地址判定**：直连暴露端口时 `X-Real-IP` / `X-Forwarded-For` 会被丢弃，改用真实 TCP 来源地址（防止伪造该头绕过 OTP 锁定与 session 绑定）；只有显式设置 `trust-proxy-headers: true`（即前面是你自己控制的反代）才采信这些头。

## For developers

- 后端：Java 25 toolchain（Gradle 自动下载）、shadowJar relocate 依赖至 `cn.apisium.nekomaid.libs.*`
- 构建：`./gradlew shadowJar --no-daemon` → `build/libs/NekoMaid.jar`（已内置 `src/main/resources/static/` 下的面板前端）
- 前端：`npm install --legacy-peer-deps` → `npm run build`（产物 `dist/`，`base: './'` 相对路径）；发布前把 `dist/` 同步到 `src/main/resources/static/`
- TLS/证书相关代码不引入任何外部加密库：`TlsMaterials`（PEM → PKCS#8）与 `SelfSignedTls`（自签 X.509）都只依赖 JDK，避免在插件类加载器环境下依赖 BouncyCastle。

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
