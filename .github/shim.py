"""
Прокси-заглушка для ForgeGradle 2.0.2.

В плагине зашиты адреса, которых больше не существует:
  export.mcpbot.bspk.rs                — MCPBot закрыт в 2022, домен не резолвится
  s3.amazonaws.com/Minecraft.Download  — Mojang вывел из эксплуатации, 404
  files.minecraftforge.net/maven       — отвечает 301, а плагин не ходит по редиректам

Сборка ходит на них по HTTP, поэтому JVM заворачивается сюда через
http.proxyHost / http.proxyPort в gradle.properties. Нужные файлы берутся
с живых зеркал, всё остальное проксируется прозрачно.

HTTPS сюда не идёт: https.proxyHost не выставлен, maven репозитории
работают напрямую.
"""

import hashlib
import http.server
import json
import socketserver
import sys
import urllib.parse
import urllib.request

DEFAULT_PORT = 8080

MCP_MAVEN = "https://maven.minecraftforge.net/de/oceanlabs/mcp"
FORGE_MAVEN = "https://maven.minecraftforge.net"
MANIFEST = "https://launchermeta.mojang.com/mc/game/version_manifest.json"

# Свой opener без прокси, иначе заглушка может зациклиться сама на себя.
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
OPENER.addheaders = [("User-Agent", "ForgeGradle-shim/1.0")]

MCP_VERSIONS = json.dumps({
    "1.8.9": {
        "stable": [22, 20],
        "stable_nodoc": [22, 20],
        "snapshot": [20161208, 20160312],
        "snapshot_nodoc": [20161208, 20160312],
    }
}).encode()

# Промо-джейсон Forge не отдаётся вообще, а нужен плагину только для
# информационной строчки в логе. Отдаём пустую, но валидную структуру.
FORGE_PROMOS = json.dumps({
    "homepage": "https://files.minecraftforge.net/",
    "name": "Forge",
    "branches": {},
    "mcversion": {},
    "promos": {},
    "number": {},
    "adfocus": "",
    "webpath": "https://maven.minecraftforge.net/net/minecraftforge/forge",
}).encode()

_versions = {}
_asset_index = {}


def log(msg):
    print("shim: " + msg, flush=True)


def fetch(url):
    with OPENER.open(url, timeout=180) as resp:
        return resp.read(), resp.headers.get("Content-Type", "application/octet-stream")


def version_json(ver):
    """Берёт манифест версии с launchermeta вместо мёртвого s3."""
    if ver in _versions:
        return _versions[ver]
    body, _ = fetch(MANIFEST)
    url = None
    for entry in json.loads(body).get("versions", []):
        if entry.get("id") == ver:
            url = entry.get("url")
            break
    if url is None:
        raise KeyError("version " + ver + " not found in manifest")
    body, _ = fetch(url)
    data = json.loads(body)
    _versions[ver] = (body, data)
    index = data.get("assetIndex") or {}
    if index.get("id") and index.get("url"):
        _asset_index[index["id"]] = index["url"]
    log("manifest " + ver + " resolved via launchermeta")
    return _versions[ver]


def mojang_route(path):
    """Восстанавливает то, что лежало на s3.amazonaws.com/Minecraft.Download."""
    parts = [p for p in path.split("?")[0].split("/") if p]
    if len(parts) < 3 or parts[0] != "Minecraft.Download":
        return None, None

    if parts[1] == "versions" and len(parts) >= 4:
        ver = parts[2]
        name = parts[3]
        if name.endswith(".json"):
            body, _ = version_json(ver)
            return body, "application/json"
        if name.endswith(".jar"):
            data = version_json(ver)[1]
            kind = "server" if "server" in name else "client"
            url = (data.get("downloads") or {}).get(kind, {}).get("url")
            if not url:
                return None, None
            log("proxying " + kind + " jar " + ver)
            return fetch(url)

    if parts[1] == "indexes":
        name = parts[2][:-5] if parts[2].endswith(".json") else parts[2]
        if name not in _asset_index:
            try:
                version_json("1.8.9")
            except Exception as exc:
                log("asset index lookup failed: " + repr(exc))
        url = _asset_index.get(name)
        if url:
            return fetch(url)

    return None, None


def resolve(host, path):
    """Возвращает (тело, тип) либо (None, None) для 404."""
    host = host.split(":")[0].lower()

    if host == "export.mcpbot.bspk.rs":
        if path.split("?")[0] == "/versions.json":
            return MCP_VERSIONS, "application/json"
        return fetch(MCP_MAVEN + path)

    if host.endswith("files.minecraftforge.net"):
        rest = path[len("/maven"):] if path.startswith("/maven") else path
        if rest.split("?")[0].rstrip("/").endswith("/net/minecraftforge/forge/json"):
            # Здесь надо ответить ИМЕННО неуспехом, и это не лень.
            # ForgeExtension.checkAndSetVersion сверяет номер сборки со списком
            # из этого джейсона. Настоящий список не отдаётся уже ниоткуда,
            # а любой синтетический приводит к "No such version exists!".
            # Когда джейсон не загрузился, плагин пропускает проверку и
            # берёт версию из build.gradle как есть — ровно то, что нужно.
            # Цена — одна строка "Error occurred parsing version!" в логе.
            log("promos json: отдаю 404 нарочно, чтобы плагин пропустил проверку версии")
            return None, None
        return fetch(FORGE_MAVEN + rest)

    if host == "s3.amazonaws.com":
        return mojang_route(path)

    # Любой другой адрес проксируем как есть: через нас идёт весь HTTP сборки,
    # включая живой resources.download.minecraft.net и прочее.
    return fetch("http://" + host + path)


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def target(self):
        """В режиме прокси клиент присылает полный URL, а не путь."""
        raw = self.path
        if raw.startswith("http://") or raw.startswith("https://"):
            split = urllib.parse.urlsplit(raw)
            path = urllib.parse.urlunsplit(("", "", split.path or "/", split.query, ""))
            return split.netloc, path
        return self.headers.get("Host", ""), raw

    def serve(self, with_body):
        host, path = self.target()
        try:
            body, ctype = resolve(host, path)
        except Exception as exc:
            log("ERROR " + host + path + " -> " + repr(exc))
            self.send_response(502)
            self.send_header("Content-Length", "0")
            self.send_header("Connection", "close")
            self.end_headers()
            return

        if body is None:
            log("404 " + host + path)
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return

        log("200 " + host + path + " (" + str(len(body)) + " bytes)")
        self.send_response(200)
        self.send_header("Content-Type", ctype or "application/octet-stream")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("ETag", '"' + hashlib.md5(body).hexdigest() + '"')
        self.send_header("Accept-Ranges", "none")
        self.end_headers()
        if with_body:
            self.wfile.write(body)

    def do_GET(self):
        self.serve(True)

    def do_HEAD(self):
        self.serve(False)

    def log_message(self, fmt, *args):
        pass


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_PORT
    log("listening on 127.0.0.1:" + str(port))
    Server(("127.0.0.1", port), Handler).serve_forever()
