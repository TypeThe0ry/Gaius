# Gaius Client

[English](README.md) | **简体中文**

Gaius 把 Minecraft Java 客户端跑在浏览器里。单人模式的游戏客户端和
集成服务器 Worker 都在同一个标签页中，不需要另起 Gaius 服务；多人模式可以
连接 Gaius Paper 插件或 RelayNode。

Gaius 是独立项目，与 Mojang Studios、Microsoft 和 Minecraft 没有隶属或背书关系。
如果要分发生成的客户端文件或游戏资源，先看
[可行性与许可说明](docs/feasibility.md)。

## 截图

![Gaius 主菜单](docs/images/gaius-main-menu.png)

![Gaius 单人游戏](docs/images/gaius-singleplayer.png)

![Gaius 多人服务器列表](docs/images/gaius-multiplayer.png)

![Gaius 玩家名称界面](docs/images/gaius-player-name.png)

## 下载

打开[最新 Release](https://github.com/TypeThe0ry/Gaius/releases/latest)，根据要
连接的服务器下载对应的 HTML：

- [Minecraft 26.2 客户端](https://github.com/TypeThe0ry/Gaius/releases/latest/download/Gaius-26.2.html)
- [Minecraft 1.21.11 客户端](https://github.com/TypeThe0ry/Gaius/releases/latest/download/Gaius-1.21.11.html)
- [SHA256 校验文件](https://github.com/TypeThe0ry/Gaius/releases/latest/download/SHA256SUMS)
- 可选 Paper 插件在同一个 Release 页面里。

HTML 文件是可直接携带的单人客户端。下载后用新版 Chrome 或 Chromium 打开，先
填写玩家名称，再点 **Singleplayer**。浏览器启动器和 Worker 需要的内容都在文件
里，单人模式不用另起网页服务器。

多人模式点 **Multiplayer**，填写 Java 服务器地址。服务器需要安装 Gaius Paper
插件，或者有能访问到目标服务器的 RelayNode。

## 从源码构建

需要 Git LFS、Python 3、Node.js LTS、`curl`、`jq`、`unzip`、`shasum`，以及目标
profile 所需的 JDK。构建过程会把不同 profile 的 Maven 状态、overlay 和浏览器
输出分开保存。

```sh
git lfs install
git lfs pull

for profile in 1.21.11 26.2; do
  export GAIUS_VERSION_PROFILE_PATH="versions/${profile}.json"
  ./port/scripts/fetch-version.sh
  ./port/scripts/remap-client.sh
  bash port/scripts/build-version-release.sh "$profile"
done
```

生成文件在 `port/web/dist/<profile>/`，可携带客户端是其中的 `Gaius.html`。本地
预览可以启动一个简单的 HTTP 服务：

```sh
python3 port/scripts/serve-dist.py --host 127.0.0.1 --port 8781
```

然后在 Chrome 打开 `/dist/26.2/` 或 `/dist/1.21.11/`。

## 目录

- `port/`：TeaVM 移植、浏览器平台代码、启动器、patcher 和构建脚本。
- `apps/bridge/`：可以自己部署的 WebSocket-to-TCP RelayNode。
- `apps/server-plugin/`：可选的 Paper 服务端插件。
- `packages/`：浏览器协议和本地世界支持代码。
- `port/web/dist/<profile>/`：按 profile 生成的输出，不要手工编辑。
- `docs/`：设计说明、Release 检查、RelayNode 说明和截图。
- `tools/`：仓库检查和发布辅助脚本。

## 多人连接

浏览器不能直接打开 Minecraft 所需的原始 TCP 连接。Gaius 会把数据流通过
WebSocket 发到 Paper 端点或 RelayNode，再由它为当前玩家连接目标服务器。RelayNode
只是传输桥接，不负责通用协议转换；客户端、目标服务器的 Minecraft 协议和登录
配置仍然要匹配。

仓库里的传输检查使用 `t40.sjcmc.cn:14803`，中间经过
`wss://ellan.site/tunnel`。自己部署 RelayNode 时要配置 TLS、允许的 Origin、目标
地址策略、限流、容量和问题反馈地址。详见 [RelayNode 指南](docs/relay-nodes.md)
和 [`apps/bridge/README.md`](apps/bridge/README.md)。

## 检查

根据改动范围运行对应检查：

```sh
python3 port/scripts/test-postprocess-index-shell.py
python3 port/scripts/test-index-template.py
node tools/check-release-metadata.mjs
node tools/check-relay-registry.mjs
node tools/check-singleplayer-lifecycle.mjs
git diff --check
```

静态检查不能代替 Chrome 实测。涉及浏览器、渲染或世界生成的改动，要打开真正
的 HTML 进入世界，走过新加载的地形，并检查画面、输入、声音和加载过程。

## 参与开发

先读 [`CONTRIBUTING.md`](CONTRIBUTING.md)，生成文件按 profile 分目录保存，并在
Pull Request 里写清实际运行过的命令。不要提交 Mojang 客户端输入、本地世界、密钥
或构建状态。发布流程见[Release 指南](docs/releasing.md)。

## 许可与归属

请保留上游声明，并阅读
[Minecraft EULA](https://www.minecraft.net/en-us/eula) 和
[Usage Guidelines](https://www.minecraft.net/en-us/usage-guidelines)。本仓库本身
不授予分发 Mojang/Microsoft 客户端代码、映射、库、资源或生成游戏文件的权利。

安全问题请走仓库的
[GitHub Security 页面](https://github.com/TypeThe0ry/Gaius/security)，不要直接
发公开 Issue。
