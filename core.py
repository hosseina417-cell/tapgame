# -*- coding: utf-8 -*-
"""هستهٔ تولید و پردازش کانفیگ V2Ray"""
import base64
import json
import re
import socket
import uuid
from urllib.parse import quote, unquote, urlparse

PROTOCOLS = ["vmess", "vless", "trojan", "shadowsocks"]
NETWORKS = ["tcp", "ws", "grpc", "h2", "quic", "kcp"]
SECURITIES = ["auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero"]
TLS_MODES = ["none", "tls", "reality"]

CONFIG_RE = re.compile(
    r"(vmess|vless|trojan|ss)://[^\s<>\"'`]+", re.IGNORECASE
)


def new_uuid():
    return str(uuid.uuid4())


def gen_vmess_link(p):
    obj = {
        "v": "2",
        "ps": p.get("remark", "V2Ray"),
        "add": p["address"],
        "port": str(p["port"]),
        "id": p["uuid"],
        "aid": str(p.get("alter_id", 0)),
        "scy": p.get("security", "auto"),
        "net": p.get("network", "tcp"),
        "type": p.get("header_type", "none"),
        "host": p.get("host", ""),
        "path": p.get("path", "/"),
        "tls": "tls" if p.get("tls") == "tls" else "",
        "sni": p.get("sni", ""),
        "alpn": p.get("alpn", ""),
        "fp": p.get("fingerprint", ""),
    }
    if p.get("tls") == "reality":
        obj["tls"] = "reality"
        obj["pbk"] = p.get("public_key", "")
        obj["sid"] = p.get("short_id", "")
    raw = json.dumps(obj, ensure_ascii=False)
    b64 = base64.b64encode(raw.encode()).decode()
    return "vmess://" + b64


def gen_vless_link(p):
    params = []
    params.append("encryption=none")
    params.append("security=" + p.get("tls", "none"))
    params.append("type=" + p.get("network", "tcp"))
    if p.get("sni"):
        params.append("sni=" + quote(p["sni"]))
    if p.get("host"):
        params.append("host=" + quote(p["host"]))
    if p.get("path"):
        params.append("path=" + quote(p["path"], safe=""))
    if p.get("flow"):
        params.append("flow=" + p["flow"])
    if p.get("public_key"):
        params.append("pbk=" + p["public_key"])
    if p.get("short_id"):
        params.append("sid=" + p["short_id"])
    if p.get("fingerprint"):
        params.append("fp=" + p["fingerprint"])
    if p.get("alpn"):
        params.append("alpn=" + p["alpn"])
    if p.get("header_type") and p.get("header_type") != "none":
        params.append("headerType=" + p["header_type"])
    if p.get("service_name"):
        params.append("serviceName=" + quote(p["service_name"]))
    if p.get("mode"):
        params.append("mode=" + p["mode"])
    q = "&".join(params)
    remark = quote(p.get("remark", "V2Ray"))
    return "vless://{}@{}:{}?{}#{}".format(p["uuid"], p["address"], p["port"], q, remark)


def gen_trojan_link(p):
    params = []
    params.append("security=" + p.get("tls", "tls"))
    params.append("type=" + p.get("network", "tcp"))
    if p.get("sni"):
        params.append("sni=" + quote(p["sni"]))
    if p.get("host"):
        params.append("host=" + quote(p["host"]))
    if p.get("path"):
        params.append("path=" + quote(p["path"], safe=""))
    if p.get("flow"):
        params.append("flow=" + p["flow"])
    if p.get("alpn"):
        params.append("alpn=" + p["alpn"])
    if p.get("fingerprint"):
        params.append("fp=" + p["fingerprint"])
    q = "&".join(params)
    remark = quote(p.get("remark", "V2Ray"))
    return "trojan://{}@{}:{}?{}#{}".format(p["password"], p["address"], p["port"], q, remark)


def gen_ss_link(p):
    method = p.get("method", "aes-256-gcm")
    password = p.get("password", "")
    userinfo = base64.b64encode("{}:{}".format(method, password).encode()).decode().rstrip("=")
    remark = quote(p.get("remark", "V2Ray"))
    hostpart = "{}:{}".format(p["address"], p["port"])
    if p.get("plugin"):
        return "ss://{}@{}?plugin={}".format(userinfo, hostpart, quote(p["plugin"])) + "#" + remark
    return "ss://{}@{}#{}".format(userinfo, hostpart, remark)


def generate_link(p):
    proto = p.get("protocol", "vmess")
    if proto == "vmess":
        return gen_vmess_link(p)
    if proto == "vless":
        return gen_vless_link(p)
    if proto == "trojan":
        return gen_trojan_link(p)
    if proto == "shadowsocks":
        return gen_ss_link(p)
    raise ValueError("unknown protocol")


def parse_vmess(link):
    b64 = link[len("vmess://"):]
    # pad
    pad = "=" * (-len(b64) % 4)
    raw = base64.b64decode(b64 + pad).decode("utf-8", errors="replace")
    obj = json.loads(raw)
    return {
        "protocol": "vmess",
        "remark": obj.get("ps", ""),
        "address": obj.get("add", ""),
        "port": int(obj.get("port", 0) or 0),
        "uuid": obj.get("id", ""),
        "alter_id": int(obj.get("aid", 0) or 0),
        "security": obj.get("scy", "auto"),
        "network": obj.get("net", "tcp"),
        "host": obj.get("host", ""),
        "path": obj.get("path", "/"),
        "tls": obj.get("tls", "") or "none",
        "sni": obj.get("sni", ""),
        "alpn": obj.get("alpn", ""),
        "fingerprint": obj.get("fp", ""),
        "public_key": obj.get("pbk", ""),
        "short_id": obj.get("sid", ""),
    }


def _parse_query(url):
    q = {}
    if "?" in url:
        qs = url.split("?", 1)[1]
        if "#" in qs:
            qs = qs.split("#", 1)[0]
        for part in qs.split("&"):
            if "=" in part:
                k, v = part.split("=", 1)
                q[unquote(k)] = unquote(v)
    remark = ""
    if "#" in url:
        remark = unquote(url.split("#", 1)[1])
    return q, remark


def parse_vless(link):
    # vless://uuid@host:port?...
    u = urlparse(link)
    q, remark = _parse_query(link)
    return {
        "protocol": "vless",
        "remark": remark,
        "address": u.hostname or "",
        "port": u.port or 0,
        "uuid": u.username or "",
        "network": q.get("type", "tcp"),
        "security": q.get("security", "none"),
        "sni": q.get("sni", ""),
        "host": q.get("host", ""),
        "path": q.get("path", "/"),
        "flow": q.get("flow", ""),
        "public_key": q.get("pbk", ""),
        "short_id": q.get("sid", ""),
        "fingerprint": q.get("fp", ""),
        "alpn": q.get("alpn", ""),
        "service_name": q.get("serviceName", ""),
        "mode": q.get("mode", ""),
        "header_type": q.get("headerType", "none"),
    }


def parse_trojan(link):
    u = urlparse(link)
    q, remark = _parse_query(link)
    return {
        "protocol": "trojan",
        "remark": remark,
        "address": u.hostname or "",
        "port": u.port or 0,
        "password": unquote(u.username or ""),
        "network": q.get("type", "tcp"),
        "security": q.get("security", "tls"),
        "sni": q.get("sni", ""),
        "host": q.get("host", ""),
        "path": q.get("path", "/"),
        "flow": q.get("flow", ""),
        "alpn": q.get("alpn", ""),
        "fingerprint": q.get("fp", ""),
    }


def parse_ss(link):
    # ss://base64@host:port#remark  or ss://base64#remark
    body = link[len("ss://"):]
    remark = ""
    if "#" in body:
        body, remark = body.split("#", 1)
        remark = unquote(remark)
    query = ""
    if "?" in body:
        body, query = body.split("?", 1)
    if "@" in body:
        userinfo, hostpart = body.rsplit("@", 1)
    else:
        # legacy: whole thing base64
        try:
            dec = base64.b64decode(userinfo + "=" * (-len(userinfo) % 4)).decode()
        except Exception:
            dec = ""
        if "@" in dec:
            userinfo, hostpart = dec.split("@", 1)
        else:
            userinfo, hostpart = "", body
    # decode userinfo
    try:
        dec = base64.b64decode(userinfo + "=" * (-len(userinfo) % 4)).decode()
    except Exception:
        dec = userinfo
    if ":" in dec:
        method, password = dec.split(":", 1)
    else:
        method, password = "aes-256-gcm", ""
    host, port = hostpart, 0
    if ":" in hostpart:
        host, portstr = hostpart.rsplit(":", 1)
        try:
            port = int(portstr)
        except Exception:
            port = 0
    plugin = ""
    if query:
        for part in query.split("&"):
            if part.startswith("plugin="):
                plugin = unquote(part[len("plugin="):])
    return {
        "protocol": "shadowsocks",
        "remark": remark,
        "address": host,
        "port": port,
        "method": method,
        "password": password,
        "plugin": plugin,
    }


def parse_link(link):
    link = link.strip()
    if link.startswith("vmess://"):
        return parse_vmess(link)
    if link.startswith("vless://"):
        return parse_vless(link)
    if link.startswith("trojan://"):
        return parse_trojan(link)
    if link.startswith("ss://"):
        return parse_ss(link)
    raise ValueError("unknown scheme")


def extract_configs(text):
    found = CONFIG_RE.findall(text)
    # CONFIG_RE only captures scheme group; need full match
    full = re.findall(r"(?:vmess|vless|trojan|ss)://[^\s<>\"'`]+", text, re.IGNORECASE)
    out = []
    seen = set()
    for f in full:
        f = f.strip().rstrip(".,);]")
        if f not in seen:
            seen.add(f)
            out.append(f)
    return out


def validate_config(cfg):
    errs = []
    if not cfg.get("address"):
        errs.append("address empty")
    port = cfg.get("port", 0)
    try:
        port = int(port)
    except Exception:
        errs.append("bad port")
        port = 0
    if port <= 0 or port > 65535:
        errs.append("port out of range")
    proto = cfg.get("protocol")
    if proto == "vmess":
        if not cfg.get("uuid"):
            errs.append("uuid empty")
    elif proto == "vless":
        if not cfg.get("uuid"):
            errs.append("uuid empty")
    elif proto == "trojan":
        if not cfg.get("password"):
            errs.append("password empty")
    elif proto == "shadowsocks":
        if not cfg.get("password"):
            errs.append("password empty")
    return errs


def check_active(cfg, timeout=5):
    """TCP connect test. Returns (ok, latency_ms, error)."""
    host = cfg.get("address")
    port = cfg.get("port")
    try:
        port = int(port)
    except Exception:
        return False, None, "bad port"
    if not host or port <= 0:
        return False, None, "missing host/port"
    try:
        t0 = __import__("time").time()
        s = socket.create_connection((host, port), timeout=timeout)
        s.close()
        ms = int((__import__("time").time() - t0) * 1000)
        return True, ms, ""
    except Exception as e:
        return False, None, str(e)


def build_client_json(cfg):
    """Build a v2ray client JSON config for the given outbound."""
    proto = cfg.get("protocol")
    outbound = {"protocol": proto if proto != "shadowsocks" else "shadowsocks", "tag": "proxy"}
    settings = {}
    if proto == "vmess":
        settings = {
            "vnext": [{
                "address": cfg["address"],
                "port": cfg["port"],
                "users": [{
                    "id": cfg["uuid"],
                    "alterId": cfg.get("alter_id", 0),
                    "security": cfg.get("security", "auto"),
                }],
            }]
        }
    elif proto == "vless":
        user = {"id": cfg["uuid"], "encryption": "none"}
        if cfg.get("flow"):
            user["flow"] = cfg["flow"]
        settings = {
            "vnext": [{
                "address": cfg["address"],
                "port": cfg["port"],
                "users": [user],
            }]
        }
    elif proto == "trojan":
        settings = {
            "servers": [{
                "address": cfg["address"],
                "port": cfg["port"],
                "password": cfg["password"],
            }]
        }
    elif proto == "shadowsocks":
        settings = {
            "servers": [{
                "address": cfg["address"],
                "port": cfg["port"],
                "method": cfg.get("method", "aes-256-gcm"),
                "password": cfg.get("password", ""),
            }]
        }
    outbound["settings"] = settings
    # streamSettings
    stream = {"network": cfg.get("network", "tcp")}
    tls = cfg.get("tls", "none")
    if tls in ("tls", "reality"):
        tls_obj = {"serverName": cfg.get("sni") or cfg["address"]}
        if cfg.get("alpn"):
            tls_obj["alpn"] = [x for x in cfg["alpn"].split(",") if x]
        if cfg.get("fingerprint"):
            tls_obj["fingerprint"] = cfg["fingerprint"]
        if tls == "reality":
            tls_obj["publicKey"] = cfg.get("public_key", "")
            tls_obj["shortId"] = cfg.get("short_id", "")
        stream["security"] = tls
        stream[tls] = tls_obj
    # tcp/ws/etc
    net = cfg.get("network", "tcp")
    if net == "ws":
        stream["wsSettings"] = {
            "path": cfg.get("path", "/"),
            "headers": {"Host": cfg.get("host") or cfg["address"]},
        }
    elif net == "grpc":
        stream["grpcSettings"] = {"serviceName": cfg.get("service_name", "")}
    elif net == "h2":
        stream["httpSettings"] = {"path": cfg.get("path", "/"), "host": [cfg.get("host") or cfg["address"]]}
    elif net == "quic":
        stream["quicSettings"] = {"security": "none", "key": "", "header": {"type": "none"}}
    elif net == "kcp":
        stream["kcpSettings"] = {"mtu": 1350, "tti": 50, "uplinkCapacity": 12, "downlinkCapacity": 100, "congestion": False, "readBufferSize": 2, "writeBufferSize": 2, "header": {"type": "none"}, "seed": ""}
    if net == "tcp" and cfg.get("header_type") and cfg.get("header_type") != "none":
        stream["tcpSettings"] = {"header": {"type": cfg["header_type"]}}
    outbound["streamSettings"] = stream

    config = {
        "log": {"loglevel": "warning"},
        "inbounds": [{"port": 10808, "protocol": "socks", "settings": {"auth": "noauth", "udp": True}, "sniffing": {"enabled": True, "destOverride": ["http", "tls"]}}],
        "outbounds": [outbound, {"protocol": "freedom", "tag": "direct"}, {"protocol": "blackhole", "tag": "block"}],
        "routing": {"domainStrategy": "IPIfNonMatch", "rules": [{"type": "field", "ip": ["geoip:private"], "outboundTag": "direct"}]},
    }
    return json.dumps(config, indent=2, ensure_ascii=False)


def parse_subscription(text):
    """Parse subscription content (base64 or plain links) into list of config links."""
    text = text.strip()
    out = []
    # try base64 decode
    try:
        dec = base64.b64decode(text + "=" * (-len(text) % 4)).decode("utf-8", errors="replace")
        if "://" in dec:
            text = dec
    except Exception:
        pass
    out.extend(extract_configs(text))
    # maybe each line is a link
    for line in text.splitlines():
        line = line.strip()
        if line and "://" in line and line not in out:
            out.append(line)
    # dedup
    seen = set(); res = []
    for l in out:
        if l not in seen:
            seen.add(l); res.append(l)
    return res
