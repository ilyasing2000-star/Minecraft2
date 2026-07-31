import http.server
import socketserver
import json
import hashlib
import urllib.request

MCP_MAVEN = "https://maven.minecraftforge.net/de/oceanlabs/mcp"
FORGE_MAVEN = "https://maven.minecraftforge.net"
MANIFEST = "https://launchermeta.mojang.com/mc/game/version_manifest.json"
UA = {"User-Agent": "Mozilla/5.0 ForgeGradle-shim"}

MCP_VERSIONS = json.dumps({
    "1.8.9": {
        "stable": [22, 20],
        "stable_nodoc": [22, 20],
        "snapshot": [20161208, 20160312],
        "snapshot_nodoc": [20161208, 20160312],
    }
}).encode()

# Заглушка для промо-джейсона Forge: он больше не отдаётся, но нужен
# плагину только для информационного сообщения о версиях.
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

_version_cache = {}
_asset_index = {}


def log(msg):
    print("shim: " + msg, flush=True)


def fetch(url):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read(), r.headers.get("Content-Type", "application/octet-stream")


def version_json(ver):
    """Достаёт манифест версии с живого launchermeta вместо мёртвого s3."""
    if ver in _version_cache:
        return _version_cache[ver]
    body, _ = fetch(MANIFEST)
    manifest = json.loads(body)
    url = None
    for entry in manifest.get("versions", []):
        if entry.get("id") == ver:
            url = entry.get("url")
            break
    if url is None:
        raise KeyError("version " + ver + " not in manifest")
    body, _ = fetch(url)
    data = json.loads(body)
    _version_cache[ver] = (body, data)
    idx = data.get("assetIndex")
    if idx and idx.get("id") and idx.get("url"):
        _asset_index[idx["id"]] = idx["url"]
    log("version json for " + ver + " resolved via launchermeta")
    return _version_cache[ver]


def mojang_route(path):
    """Отдаёт то, что раньше лежало на s3.amazonaws.com/Minecraft.Download."""
    parts = [p for p in path.split("?")[0].split("/") if p]
    if len(parts) >= 4 and parts[0] == "Minecraft.Download" and parts[1] == "versions":
        ver = parts[2]
        name = parts[3]
        if name.endswith(".json"):
            body, _ = version_json(ver)
            return body, "application/json"
        if name.endswith(".jar"):
            _, data = version_json(ver)
            kind = "server" if "server" in name else "client"
            url = data.get("downloads", {}).get(kind, {}).get("url")
            if not url:
                return None, None
            log("proxying " + kind + " jar for " + ver)
            return fetch(url)
    if len(parts) >= 3 and parts[0] == "Minecraft.Download" and parts[1] == "indexes":
        name = parts[2][:-5] if parts[2].endswith(".json") else parts[2]
        if name not in _asset_index:
            try:
                version_json("1.8.9")
            except Exception:
                pass
        url = _asset_index.get(name)
        if url:
            return fetch(url)
    return None, None


def resolve(host, path):
    """Возвращает (тело, тип) либо (None, None), если адрес не наш."""
    host = host.split(":")[0].lower()

    if host == "export.mcpbot.bspk.rs":
        if path.startswith("/versions.json"):
            return MCP_VERSIONS, "application/json"
        return fetch(MCP_MAVEN + path)

    if host in ("files.minecraftforge.net", "www.files.minecraftforge.net"):
        rest = path[len("/maven"):] if path.startswith("/maven") else path
        if rest.rstrip("/").endswith("/net/minecraftforge/forge/json"):
            return FORGE_PROMOS, "application/json"
        return fetch(FORGE_MAVEN + rest)

    if host in ("s3.amazonaws.com", "resources.download.minecraft.net"):
        return mojang_route(path)

    return None, None


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _serve(self, with_body):
        host = self.headers.get("Host", "")
        try:
            body, ctype = resolve(host, self.path)
        except Exception as exc:
            log("ERROR " + host + self.path + " -> " + repr(exc))
            self.send_error(502, "shim upstream failure")
            return
        if body is None:
            log("404 " + host + self.path)
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        log("200 " + host + self.path + " (" + str(len(body)) + " bytes)")
        self.send_response(200)
        self.send_header("Content-Type", ctype or "application/octet-stream")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("ETag", '"' + hashlib.md5(body).hexdigest() + '"')
        self.send_header("Accept-Ranges", "none")
        self.end_headers()
        if with_body:
            self.wfile.write(body)

    def do_GET(self):
        self._serve(True)

    def do_HEAD(self):
        self._serve(False)

    def log_message(self, fmt, *args):
        pass


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    log("listening on 127.0.0.1:80")
    Server(("127.0.0.1", 80), Handler).serve_forever()
