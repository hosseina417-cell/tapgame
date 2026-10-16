# -*- coding: utf-8 -*-
"""جستجو در تلگرام و وب برای یافتن کانفیگ‌های V2Ray"""
import re
import socket
import urllib.request
import urllib.parse
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

def _fetch(url, timeout=15):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "en-US,en;q=0.9"})
    ctx = None
    try:
        import ssl, certifi
        ctx = ssl.create_default_context(cafile=certifi.where())
    except Exception:
        ctx = None
    with urllib.request.urlopen(req, timeout=timeout, context=ctx) as r:
        data = r.read()
    try:
        return data.decode("utf-8", errors="replace")
    except Exception:
        return data.decode("latin-1", errors="replace")


def fetch_telegram_channel(channel, limit=200):
    """Fetch public channel posts via t.me/s/<channel> and extract configs."""
    channel = channel.strip().lstrip("@")
    url = "https://t.me/s/" + channel
    try:
        html = _fetch(url)
    except Exception as e:
        return [], str(e)
    # messages text
    # t.me/s page contains message text in divs with class tgme_widget_message_text
    texts = re.findall(r'<div class="tgme_widget_message_text[^"]*"[^>]*>(.*?)</div>', html, re.S)
    # fallback: any text
    if not texts:
        texts = [html]
    out = []
    for t in texts:
        # strip tags
        clean = re.sub(r"<[^>]+>", " ", t)
        clean = re.sub(r"\s+", " ", clean)
        out.append(clean)
    return out, ""


def search_duckduckgo(query, max_results=30):
    url = "https://html.duckduckgo.com/html/?q=" + urllib.parse.quote(query)
    try:
        html = _fetch(url)
    except Exception as e:
        return [], str(e)
    # result links
    links = re.findall(r'href="(https?://[^"]+)"', html)
    # filter redirect
    res = []
    for l in links:
        if "duckduckgo.com" in l:
            m = re.search(r'uddg=([^&]+)', l)
            if m:
                l = urllib.parse.unquote(m.group(1))
        if l.startswith("http"):
            res.append(l)
    # dedup
    seen = set()
    out = []
    for l in res:
        if l not in seen:
            seen.add(l)
            out.append(l)
    return out[:max_results], ""


def search_google(query, max_results=30):
    # Google may block; try anyway
    url = "https://www.google.com/search?q=" + urllib.parse.quote(query) + "&num=30"
    try:
        html = _fetch(url)
    except Exception as e:
        return [], str(e)
    links = re.findall(r'href="/url\?q=([^&"]+)', html)
    out = []
    seen = set()
    for l in links:
        l = urllib.parse.unquote(l)
        if l.startswith("http") and "google.com" not in l:
            if l not in seen:
                seen.add(l)
                out.append(l)
    if not out:
        # fallback: any http links
        for l in re.findall(r'href="(https?://[^"]+)"', html):
            if "google" not in l and l not in seen:
                seen.add(l)
                out.append(l)
    return out[:max_results], ""


def fetch_url_text(url):
    try:
        return _fetch(url), ""
    except Exception as e:
        return "", str(e)


def is_telegram_channel(s):
    return bool(re.match(r"^@?[A-Za-z0-9_]{4,}$", s.strip()))


COMMON_CHANNELS = [
    "v2rayngvpn",
    "V2rayNG",
    "v2ray_configs",
    "FreeV2ray",
    "V2rayN",
]


def search_all(query, channels=None, use_google=True, use_ddg=True, max_workers=6):
    """Return list of extracted config links + source info."""
    from core import extract_configs, parse_subscription
    results = []
    seen = set()
    lock = __import__("threading").Lock()

    def add_links(links, source):
        for l in links:
            l = l.strip().rstrip(".,);]")
            if l and l not in seen:
                with lock:
                    if l not in seen:
                        seen.add(l)
                        results.append({"link": l, "source": source, "status": "unknown"})

    def handle_channel(ch):
        texts, err = fetch_telegram_channel(ch)
        for t in texts:
            for l in extract_configs(t):
                add_links([l], "telegram:" + ch)
        # also subscription-style content
        for t in texts:
            for l in parse_subscription(t):
                add_links([l], "telegram:" + ch)

    chans = channels if channels else COMMON_CHANNELS
    with ThreadPoolExecutor(max_workers=max_workers) as ex:
        futures = []
        for ch in chans:
            futures.append(ex.submit(handle_channel, ch))
        for f in as_completed(futures):
            try:
                f.result()
            except Exception:
                pass

    # Web search
    q = query.strip() if query else "vmess vless trojan config"
    urls = []
    if use_ddg:
        u, err = search_duckduckgo(q + " vmess vless trojan", max_results=15)
        urls += [(u_, "ddg") for u_ in u]
    if use_google:
        u, err = search_google(q + " vmess vless trojan", max_results=15)
        urls += [(u_, "google") for u_ in u]

    def handle_url(u, tag):
        txt, e = fetch_url_text(u)
        if not txt:
            return
        for l in extract_configs(txt):
            add_links([l], "web:" + u[:36])
        for l in parse_subscription(txt):
            add_links([l], "web:" + u[:36])

    with ThreadPoolExecutor(max_workers=max_workers) as ex:
        futures = [ex.submit(handle_url, u, t) for u, t in urls]
        for f in as_completed(futures):
            try:
                f.result()
            except Exception:
                pass

    # input itself
    for l in extract_configs(q):
        add_links([l], "input")
    for l in parse_subscription(q):
        add_links([l], "input")

    return results
