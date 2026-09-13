# NekoMaid 部署文档（通用版）

> 本文档面向任何想自建 NekoMaid 面板的服主，所有示例使用占位符（`example.com`、`/path/to/server`），请替换为你的实际环境。
> 支持版本线：**Paper 1.21.11**（最低 Java 21）与 **Paper 26.2**（最低 Java 25），下文以 1.21.11 为例，差异处单独标注。

## 0. 架构总览

```
浏览器（前端面板，https）──► nginx（TLS 终结）──► NekoMaid（明文 HTTP，仅监听本机回环）
```

- 前端面板是静态站点（Vite 构建），可托管在任意静态服务（nginx / GitHub Pages / CDN）。
- NekoMaid 插件内置独立 HTTP 服务器（**不需要任何前置插件**），通过 nginx 反向代理对外提供 HTTPS 访问。
- socket.io 使用 WebSocket/长轮询升级，nginx 需配置 Upgrade/Connection 头。

## 1. 构建

### 1.1 后端（插件）

要求：JDK 21+（编译用 Gradle toolchain，自动下载）。

```bash
./gradlew shadowJar --no-daemon
# 产物：build/libs/NekoMaid-1.0-SNAPSHOT.jar
```

要点：
- `compileOnly` 依赖（netty、paper-api、Multiverse 等）**不进 jar**，由服务端/插件提供。
- `implementation` 依赖被 relocate 到 `cn.apisium.nekomaid.libs.*`。
- jar 内**没有前端资源**，前端单独构建（见 §4）。

### 1.2 前端（可选，若使用插件内 static-path 或托管面板）

见 §4。

## 2. 后端部署

```bash
# 1) 把 jar 放到服务器插件目录
#    方式不限：scp / FTP / 面板上传。务必先备份服务器上旧版 jar。
scp build/libs/NekoMaid-1.0-SNAPSHOT.jar YOUR_USER@YOUR_SERVER:/tmp/NekoMaid-new.jar
ssh YOUR_USER@YOUR_SERVER
  cp /path/to/server/plugins/NekoMaid.jar /path/to/server/plugins/NekoMaid.jar.bak.$(date +%s)
  mv /tmp/NekoMaid-new.jar /path/to/server/plugins/NekoMaid.jar

# 2) 重启服务器（screen / tmux / systemd 任选）
#    在服务器控制台执行 stop，等进程退出后再启动
```

**首次启动**：插件自动生成 `plugins/NekoMaid/config.yml` 并创建一个无 2fa 的主令牌。**主令牌必须配置 TOTP 后才能连接**（见 §5）。

### 2.1 config.yml

```yaml
tokens:                      # 统一令牌列表
  - name: primary            # primary: true = 主令牌（全权限，管理其它令牌）
    token: <自动生成>
    secret: ''               # 主令牌 TOTP secret，/nm 2fa setup 生成
    primary: true
  # - name: op1              # 副令牌（受限，无 primary 标记）
  #   token: <uuid>
  #   secret: <base32>
  #   permissions: [dashboard, playerList, players, worlds, profiler, scheduler, entity, block]

hostname: 127.0.0.1          # 公网连接地址，必须带端口（如 example.com:12333）
customAddress: ''            # 自定义管理地址模板
port: 12334                  # HTTP 监听端口（建议仅本机监听 + nginx TLS 终结）
static-path: static          # 静态资源目录（相对插件数据目录或绝对路径）
gzip: true

skin-url: ''                 # 自定义皮肤 URL（{} 替换为用户名或 UUID）
head-url: ''                 # 自定义头像 URL（{} 替换为用户名或 UUID）

geolite2-eula: false         # 是否同意 MaxMind GeoIP2 EULA
baidu-map-license-key: ''    # 百度地图密钥（仅前端部署时使用，由服务端下发）

logger:
  maxLevel: 'INFO'
  minLevel: 'OFF'

debug: false                 # 调试模式（打印异常栈，排查后关回）
```

- `hostname` **必须带端口**，否则 `/nekomaid` 生成的连接 URL 端口会错。
- 改完 config 执行 `nm reload`（只重读 config，不重启 HTTP 服务器）。
- `saveDefaultConfig()` 只在文件不存在时写入默认配置，**已有 config.yml 不会被新 jar 覆盖**。

## 3. nginx 反向代理（TLS 终结）

> 推荐：NekoMaid 只监听 `127.0.0.1:12334`，公网全部由 nginx 以 HTTPS 暴露。**不要**把插件监听改到 0.0.0.0 裸奔。

```nginx
# /etc/nginx/sites-available/nekomaid
map $http_upgrade $connection_upgrade {
    default upgrade;
    ''      close;
}

server {
    listen 12333 ssl;
    listen [::]:12333 ssl;
    http2 on;
    server_name example.com;

    ssl_certificate     /etc/nginx/certs/fullchain.pem;   # 换成你的证书路径
    ssl_certificate_key /etc/nginx/certs/key.pem;
    ssl_protocols       TLSv1.2 TLSv1.3;

    location /NekoMaid/ {
        proxy_pass http://127.0.0.1:12334;                # 与 config.yml 的 port 一致
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;           # socket.io WebSocket 必需
        proxy_set_header Connection $connection_upgrade;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

验证并重载：`nginx -t && nginx -s reload`。

**为什么必须 HTTPS 反代**：socket.io-client 在 https 页面会把不带协议的连接地址用 `location.protocol` 补全成 https/wss；NekoMaid 是明文 HTTP，直接暴露必然 `ERR_SSL_PROTOCOL_ERROR`。前端页面协议必须与 socket.io 协议一致。

## 4. 前端构建与部署

> 前端可独立部署（推荐，nginx 静态托管 / GitHub Pages），也可由插件 `static-path` 提供服务。

### 4.1 构建

```bash
# 1) 依赖（minecraft-render 为可选依赖，缺原生编译工具链不阻断安装）
npm install --legacy-peer-deps --no-audit --no-fund

# 2) Minecraft 语言文件（zh_cn + en_us）
npm run gen:minecraft:language
# 注意：新版 assetIndex 没有 en_us.json（内置进 jar），用 en_gb.json 另存为 en_us.json

# 3) Minecraft 物品图标
#    minecraft-render 不可用时的替代方案：
#    下载对应版本 client.jar → 解 assets/minecraft/textures/item/*.png + block/*.png
#    到 icons/minecraft/（item 优先覆盖）→ 生成 minecraftIcons.json

# 4) material 图标（vscode-material-icon-theme，需 v4.x 旧版 CJS 结构）
git clone --depth 1 --branch v4.34.0 \
  https://github.com/PKief/vscode-material-icon-theme.git vscode-material-icon-theme
npm run gen:material:icons
cp vscode-material-icon-theme/icons/*.svg icons/material/

# 5) 拷图标进 public
mkdir -p public/icons
cp -r icons/minecraft public/icons/
cp -r icons/material public/icons/

# 6) 构建（vite base './' 相对路径，适合任意静态托管）
npm run build                 # 产物 dist/
```

### 4.2 部署（nginx 静态托管示例）

```bash
# 上传 dist 到服务器（带时间戳目录方便回滚）
scp -r dist YOUR_USER@YOUR_SERVER:/tmp/nekomaid-frontend
ssh YOUR_USER@YOUR_SERVER
  mkdir -p /var/www/nekomaid
  cp -r /tmp/nekomaid-frontend/* /var/www/nekomaid/
```

```nginx
server {
    listen 33333 ssl;
    listen [::]:33333 ssl;
    http2 on;
    server_name example.com;

    ssl_certificate     /etc/nginx/certs/fullchain.pem;
    ssl_certificate_key /etc/nginx/certs/key.pem;
    ssl_protocols       TLSv1.2 TLSv1.3;

    root /var/www/nekomaid;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;   # SPA 路由回退
    }

    location /assets/ {                     # 文件名带 hash，长缓存
        expires 30d;
        add_header Cache-Control "public, immutable";
    }
}
```

### 4.3 访问地址

浏览器打开 `https://example.com:33333/?example.com:12333/NekoMaid?<token>`
（query 里是 socket.io 连接地址；前端用 `location.protocol` 补全协议）。

## 5. 令牌与两步验证

在服务器控制台执行：

```
nm help                      # 帮助
nm                           # 输出管理地址
nm token list                # 列出令牌（[PRIMARY] 标记主令牌）
nm token create op1 Bob      # 创建副令牌 op1（绑定玩家 Bob），输出 token + secret + 二维码
nm token revoke op1          # 撤销副令牌
nm token bind Bob            # 把主令牌绑定到玩家 Bob（op Bob 时跳过副 token 生成）
nm 2fa setup                 # 为主令牌生成 secret，输出 secret + 二维码 PNG
nm 2fa enable 123456         # 输入验证器当前 6 位码，校验通过即启用主令牌 2fa
nm 2fa status                # 查看主令牌 2fa 状态
nm 2fa disable               # 清空主令牌 secret（主令牌将无法连接，需重新 setup）
nm temp                  # 生成临时 token（60 分钟有效，无需两步验证）
```

- 主/副令牌强制 TOTP（fail-closed）：**连接必须提供匹配的 OTP，否则拒绝**，5 次失败锁 5 分钟；**临时令牌（`nm temp`）为单因子，无需两步验证**（60 分钟有效）。
- 通过 token+OTP 后自动签发 8 小时 session（绑定源 IP，前端存 sessionStorage）；期间刷新/重连免输 OTP，换 IP 或过期需重输。
- 主令牌（`primary: true`）全权限；副令牌默认只读 + 玩家管理，可在面板「令牌管理」页调整权限（terminal/plugins/files/config 等默认禁用）。
- `/op <玩家>` 自动为该玩家生成副 token 并游戏内发链接；`/deop` 自动撤销；`nm token bind <你的游戏名>` 绑定后 op 自己会跳过。
- 密钥只保存在 `config.yml`，前端不接触。

## 6. 验证清单（部署完必跑）

```bash
# 1) 后端 socket.io 握手（公网 TLS）
curl -sk "https://example.com:12333/NekoMaid/?EIO=4&transport=polling&t=X"
#   期望：0{"sid":"...","upgrades":["websocket"],...}

# 2) POST connect
SID=$(curl -sk "https://example.com:12333/NekoMaid/?EIO=4&transport=polling&t=X" | grep -oP '"sid":\s*"\K[^"]+')
curl -sk -X POST -H "Content-Type: text/plain;charset=UTF-8" \
  --data-binary '40{"token":"<token>"}' \
  "https://example.com:12333/NekoMaid/?EIO=4&transport=polling&t=Y&sid=$SID"
#   期望：200 + body "ok"（502/空 = 后端异常，看 §7）

# 3) CORS 单套
curl -sk -D - -o /dev/null -H "Origin: https://example.com:33333" \
  "https://example.com:12333/NekoMaid/?EIO=4&transport=polling&t=Z"
#   期望：只有一套 Access-Control-Allow-*（engine.io 内置，勿手动加）

# 4) 前端页面
curl -sk "https://example.com:33333/" | head -c 200          # 期望 index.html

# 5) 浏览器实测
# 打开面板 → 连接 → 输 OTP → 面板加载、无 console 错误、实时数据（TPS/内存）刷新
```

## 7. 故障排查速查

| 症状 | 原因 | 处理 |
|---|---|---|
| `ERR_SSL_PROTOCOL_ERROR` | 页面 https 连了明文端口 | 确认 socket.io 走 nginx TLS 端口 |
| `ERR_CONNECTION_REFUSED` | 服务端重启窗口期（~35s） | 等启动完成再访问 |
| 每个 POST `40{...}` 打不过去（502/空） | 后端 Netty 线程异常（如第三方插件版本不兼容） | `config.yml debug: true` + `nm reload` → 复现 → 看日志异常栈 → 修复后关回 |
| CORS `multiple values` | 手动加了 CORS | 删除手动 CORS，只用 engine.io 内置 |
| 面板连接但报 token 错误 | token 与 config.yml 不符 | 用 `/nm temp` 生成临时 token，或核对 config |
| 命令生成的 URL 端口错 | hostname 没带端口 | 改 `hostname: 'example.com:12333'` + `nm reload` |
| 物品图标不显示 | 前端构建缺图标步骤 | 重跑 §4.1 的 3/5 步 |

## 8. 安全基线

- ✅ 插件只监听 `127.0.0.1`，公网仅暴露 nginx TLS
- ✅ 统一 tokens 列表：主令牌全权限，副令牌受权限白名单限制；主/副 token 强制 TOTP（fail-closed，5 次失败锁 5 分钟），临时 token（`nm temp`）单因子 60 分钟有效
- ✅ 会话凭据（8h session，绑定源 IP），减少重复输 OTP
- ✅ 副 token 权限门覆盖全部高危模块（Terminal/Plugins/Files/ServerConfig/Vault/Editors/Inventory/Scheduler 执行），默认只读 + 玩家管理
- ✅ FilesManager 三层路径校验（normalize+startsWith、逐段 NOFOLLOW 软链拒绝、toRealPath），zip slip 双保险，copy/rename 双端校验
- ✅ 上传下载 UUID 一次性能力令牌（15 分钟）+ 请求时软链复核（TOCTOU 防御）
- ✅ 命令权限节点 neko.maid.use / neko.maid.admin / neko.maid.2fa
- ⚠️ 主令牌 = 服务器管理员凭据（终端命令、插件加载、文件读写全权），泄露即 RCE 面，务必 HTTPS + TOTP
- ⚠️ IP 头（X-Real-IP）信任依赖「仅监听 127.0.0.1 + nginx 反代覆盖 $remote_addr」，勿把监听改回 0.0.0.0
