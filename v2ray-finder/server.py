#!/usr/bin/env python3
"""Public V2Ray subscription aggregator — local proxy + static UI."""
from __future__ import annotations

import json
import os
import re
import threading
import time
import urllib.error
import urllib.request
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

ROOT = os.path.dirname(os.path.abspath(__file__))
CACHE_TTL = 180
USER_AGENT = "V2RayFinder/5.0 (public-subscription aggregator)"

SOURCES = [
    {
        "id": "mahdibland",
        "name": "V2Ray Aggregator",
        "url": "https://raw.githubusercontent.com/mahdibland/V2RayAggregator/master/sub/sub_merge.txt",
    },
    {
        "id": "ebrasha",
        "name": "Free V2Ray (EbraSha)",
        "url": "https://raw.githubusercontent.com/EbraSha/free-v2ray/main/v2ray.txt",
    },
    {
        "id": "mfuu",
        "name": "mfuu v2ray",
        "url": "https://raw.githubusercontent.com/mfuu/v2ray/master/v2ray",
    },
    {
        "id": "peasoft",
        "name": "peasoft list",
        "url": "https://raw.githubusercontent.com/peasoft/NoMoreWalls/master/list.txt",
    },
    {
        "id": "barry",
        "name": "Barry-san v2ray",
        "url": "https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/All_Configs_Sub.txt",
    },
]

PROTO_RE = re.compile(
    r"((?:vmess|vless|trojan|ss|ssr|hysteria2|hy2|tuic|wireguard)://[^\s<>\"']+)",
    re.IGNORECASE,
)

_cache = {"ts": 0.0, "payload": None}
_lock = threading.Lock()


def _fetch(url: str, timeout: int = 18) -> tuple[str, str | None]:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError:
            text = raw.decode("latin-1", errors="replace")
        return text, None
    except Exception as exc:  # noqa: BLE001
        return "", str(exc)


def _b64decode(data: str) -> str:
    import base64

    s = re.sub(r"\s+", "", data)
    pad = "=" * ((4 - len(s) % 4) % 4)
    try:
        return base64.b64decode(s + pad).decode("utf-8", errors="replace")
    except Exception:
        return data


def _extract_links(text: str) -> list[str]:
    if not re.search(r"vmess://|vless://|trojan://|ss://|hy2://|hysteria2://", text, re.I):
        decoded = _b64decode(text)
        if decoded != text:
            text = decoded
    links = PROTO_RE.findall(text)
    cleaned = []
    for link in links:
        link = link.strip().rstrip(".,;)")
        if len(link) > 12:
            cleaned.append(link)
    return cleaned


def _flag_from_remark(remark: str) -> str:
    flags = {
        "US": "🇺🇸", "USA": "🇺🇸", "UNITED": "🇺🇸",
        "DE": "🇩🇪", "GERMANY": "🇩🇪",
        "NL": "🇳🇱", "NETHERLANDS": "🇳🇱",
        "GB": "🇬🇧", "UK": "🇬🇧", "BRITAIN": "🇬🇧",
        "FR": "🇫🇷", "FRANCE": "🇫🇷",
        "TR": "🇹🇷", "TURKEY": "🇹🇷", "TÜRK": "🇹🇷",
        "IR": "🇮🇷", "IRAN": "🇮🇷",
        "JP": "🇯🇵", "JAPAN": "🇯🇵",
        "SG": "🇸🇬", "SINGAPORE": "🇸🇬",
        "HK": "🇭🇰", "HONG": "🇭🇰",
        "TW": "🇹🇼", "TAIWAN": "🇹🇼",
        "CA": "🇨🇦", "CANADA": "🇨🇦",
        "FI": "🇫🇮", "FINLAND": "🇫🇮",
        "SE": "🇸🇪", "SWEDEN": "🇸🇪",
        "AE": "🇦🇪", "UAE": "🇦🇪",
        "RU": "🇷🇺", "RUSSIA": "🇷🇺",
        "IN": "🇮🇳", "INDIA": "🇮🇳",
        "KR": "🇰🇷", "KOREA": "🇰🇷",
        "AU": "🇦🇺", "AUSTRALIA": "🇦🇺",
        "IT": "🇮🇹", "ITALY": "🇮🇹",
        "ES": "🇪🇸", "SPAIN": "🇪🇸",
        "PL": "🇵🇱", "POLAND": "🇵🇱",
        "AT": "🇦🇹", "AUSTRIA": "🇦🇹",
        "CH": "🇨🇭", "SWISS": "🇨🇭",
        "BR": "🇧🇷", "BRAZIL": "🇧🇷",
    }
    up = remark.upper()
    for key, flag in flags.items():
        if key in up:
            return flag
    m = re.search(r"[\U0001F1E6-\U0001F1FF]{2}", remark)
    return m.group(0) if m else "🌐"


def _parse_one(link: str, source: str) -> dict | None:
    try:
        proto = link.split("://", 1)[0].lower()
        if proto == "hy2":
            proto = "hysteria2"
        remark = ""
        host = ""
        port = ""
        if proto == "vmess":
            body = link.split("://", 1)[1].split("#", 1)[0]
            raw = _b64decode(body)
            try:
                obj = json.loads(raw)
            except Exception:
                obj = {}
            remark = str(obj.get("ps") or obj.get("add") or "vmess")
            host = str(obj.get("add") or "")
            port = str(obj.get("port") or "")
        else:
            rest = link.split("://", 1)[1]
            if "#" in rest:
                rest, remark = rest.split("#", 1)
                from urllib.parse import unquote

                remark = unquote(remark)
            else:
                remark = proto
            parsed = urlparse("x://" + rest)
            host = parsed.hostname or ""
            port = str(parsed.port or "")
        if not host:
            return None
        return {
            "id": f"{proto}:{host}:{port}:{hash(link) & 0xFFFFFFFF:x}",
            "protocol": proto,
            "host": host,
            "port": port,
            "remark": remark[:80] or host,
            "flag": _flag_from_remark(remark + " " + host),
            "link": link,
            "source": source,
        }
    except Exception:
        return None


def collect() -> dict:
    now = time.time()
    with _lock:
        if _cache["payload"] and now - _cache["ts"] < CACHE_TTL:
            return _cache["payload"]

    sources_out = []
    seen = set()
    configs = []
    for src in SOURCES:
        text, err = _fetch(src["url"])
        links = _extract_links(text) if text else []
        added = 0
        for link in links:
            key = link.split("#")[0]
            if key in seen:
                continue
            seen.add(key)
            item = _parse_one(link, src["id"])
            if item:
                configs.append(item)
                added += 1
        sources_out.append(
            {
                "id": src["id"],
                "name": src["name"],
                "ok": err is None,
                "error": err,
                "count": added,
            }
        )

    payload = {
        "updated": int(now),
        "ttl": CACHE_TTL,
        "total": len(configs),
        "sources": sources_out,
        "configs": configs,
    }
    with _lock:
        _cache["ts"] = now
        _cache["payload"] = payload
    return payload


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=ROOT, **kwargs)

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        self.send_header("Access-Control-Allow-Origin", "*")
        super().end_headers()

    def do_OPTIONS(self):  # noqa: N802
        self.send_response(204)
        self.end_headers()

    def do_GET(self):  # noqa: N802
        parsed = urlparse(self.path)
        if parsed.path in ("/api/configs", "/api/refresh"):
            if parsed.path == "/api/refresh":
                with _lock:
                    _cache["ts"] = 0
                    _cache["payload"] = None
            data = json.dumps(collect(), ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        if parsed.path in ("/", "/index.html"):
            self.path = "/index.html"
        return super().do_GET()

    def log_message(self, fmt, *args):
        print("[v2ray-finder]", fmt % args)


def main():
    port = int(os.environ.get("PORT", "8787"))
    httpd = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    print(f"V2Ray Finder listening on 0.0.0.0:{port}", flush=True)
    httpd.serve_forever()


if __name__ == "__main__":
    main()
