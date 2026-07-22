# GaiusServerBridge

Optional Paper 1.21.11 plugin that exposes the Gaius WebSocket tunnel protocol
next to a Java server. It forwards only to the same server's local Minecraft
port, so it replaces an external Gaius Bridge without becoming a general TCP
proxy. Normal Java clients continue to use the ordinary Minecraft port.

Build with Java 21:

```sh
../../port/mvnw -f pom.xml package
```

Copy `target/gaius-server-plugin-0.1.0-SNAPSHOT.jar` into the Paper server's
`plugins/` directory. The default WebSocket endpoint is
`ws://server.example:8081/tunnel`. Use a TLS reverse proxy and `wss://` when the
browser page is served over HTTPS. Set `access-token` and restrict
`allowed-origins` for a public deployment.

Configure the browser launcher with the plugin endpoint as its Bridge URL. The
wire protocol is identical to `apps/bridge/dist/main.js`, so the browser can
fall back to an external relay when the plugin is absent.
