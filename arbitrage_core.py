"""
هستهٔ برنامه پایش آربیتراژ رمزارز بین صرافی‌های ایرانی.

این ماژول هیچ وابستگی خارجی ندارد و فقط از APIهای عمومی برای دریافت قیمت
بهترین خرید/فروش استفاده می‌کند. خروجی صرفاً تحلیلی است و سفارش‌گذاری انجام
نمی‌دهد.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
import json
import time
import urllib.error
import urllib.request
from typing import Any, Dict, Iterable, List, Mapping, Optional, Sequence, Tuple


SUPPORTED_ASSETS: Tuple[str, ...] = ("USDT", "BTC", "ETH", "TRX", "TON", "BNB", "DOGE", "XRP")
QUOTE_CURRENCY = "IRT"  # تومان
DEFAULT_TIMEOUT = 12
USER_AGENT = "ArenaArbitrageMonitor/1.0 (+public market data only)"


@dataclass(frozen=True)
class Quote:
    exchange: str
    asset: str
    bid: Decimal  # بهترین قیمت خرید صرافی از کاربر؛ قیمت مناسب فروش کاربر
    ask: Decimal  # بهترین قیمت فروش صرافی به کاربر؛ قیمت مناسب خرید کاربر
    quote_currency: str = QUOTE_CURRENCY
    volume: Optional[Decimal] = None
    timestamp: float = field(default_factory=time.time)
    source: str = "public-api"

    @property
    def mid(self) -> Decimal:
        return (self.bid + self.ask) / Decimal("2")


@dataclass(frozen=True)
class Opportunity:
    asset: str
    buy_exchange: str
    sell_exchange: str
    buy_price: Decimal
    sell_price: Decimal
    gross_profit: Decimal
    gross_percent: Decimal
    estimated_cost_percent: Decimal
    net_profit: Decimal
    net_percent: Decimal
    risk_note: str


@dataclass(frozen=True)
class FetchResult:
    quotes: List[Quote]
    errors: Dict[str, str]
    fetched_at: float = field(default_factory=time.time)


class MarketDataError(RuntimeError):
    pass


def to_decimal(value: Any) -> Optional[Decimal]:
    """تبدیل امن مقدار API به Decimal."""
    if value is None:
        return None
    try:
        if isinstance(value, Decimal):
            return value
        if isinstance(value, (int, float)):
            value = str(value)
        text = str(value).strip().replace(",", "")
        if not text or text.lower() in {"none", "null", "nan"}:
            return None
        number = Decimal(text)
        if number <= 0:
            return None
        return number
    except (InvalidOperation, ValueError):
        return None


def format_money(value: Decimal, max_decimals: int = 0) -> str:
    """نمایش خوانای تومان؛ برای USDT و دارایی‌های گران هم جداکننده هزارگان دارد."""
    q = Decimal("1") if max_decimals == 0 else Decimal("1").scaleb(-max_decimals)
    rounded = value.quantize(q, rounding=ROUND_HALF_UP)
    return f"{rounded:,.{max_decimals}f}"


def http_json(url: str, method: str = "GET", payload: Optional[Mapping[str, Any]] = None,
              timeout: int = DEFAULT_TIMEOUT) -> Any:
    data = None
    headers = {"User-Agent": USER_AGENT, "Accept": "application/json"}
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            charset = response.headers.get_content_charset() or "utf-8"
            return json.loads(response.read().decode(charset, "replace"))
    except urllib.error.HTTPError as exc:
        body = exc.read(300).decode("utf-8", "replace") if exc.fp else ""
        raise MarketDataError(f"HTTP {exc.code}: {body}") from exc
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
        raise MarketDataError(str(exc)) from exc


class ExchangeProvider:
    name = "Exchange"
    fee_rate = Decimal("0.0035")

    def fetch_quotes(self, assets: Sequence[str]) -> List[Quote]:
        raise NotImplementedError


class NobitexProvider(ExchangeProvider):
    name = "Nobitex"
    fee_rate = Decimal("0.0035")
    endpoint = "https://api.nobitex.ir/market/stats"

    def fetch_quotes(self, assets: Sequence[str]) -> List[Quote]:
        wanted = [asset.lower() for asset in assets]
        payload = {"srcCurrency": ",".join(wanted), "dstCurrency": "rls"}
        data = http_json(self.endpoint, "POST", payload)
        stats = data.get("stats", {}) if isinstance(data, dict) else {}
        quotes: List[Quote] = []
        for asset in assets:
            item = stats.get(f"{asset.lower()}-rls") or stats.get(f"{asset.upper()}-RLS")
            if not isinstance(item, dict) or item.get("isClosed") is True:
                continue
            bid_rial = to_decimal(item.get("bestBuy"))
            ask_rial = to_decimal(item.get("bestSell"))
            if bid_rial and ask_rial:
                quotes.append(Quote(
                    exchange=self.name,
                    asset=asset.upper(),
                    bid=bid_rial / Decimal("10"),
                    ask=ask_rial / Decimal("10"),
                    volume=to_decimal(item.get("volumeSrc") or item.get("volume")),
                    source=self.endpoint,
                ))
        return quotes


class BitpinProvider(ExchangeProvider):
    name = "Bitpin"
    fee_rate = Decimal("0.0032")
    # API غیررسمی عمومی بازار؛ اگر ساختار پاسخ تغییر کند parser چند شکل رایج را پوشش می‌دهد.
    ticker_endpoint = "https://api.bitpin.ir/v1/mkt/markets/"
    orderbook_endpoint = "https://api.bitpin.ir/v2/mth/orderbook/{symbol}/"

    def fetch_quotes(self, assets: Sequence[str]) -> List[Quote]:
        try:
            quotes = self._fetch_from_tickers(assets)
            if quotes:
                return quotes
        except MarketDataError:
            pass
        return self._fetch_from_orderbooks(assets)

    def _fetch_from_tickers(self, assets: Sequence[str]) -> List[Quote]:
        data = http_json(self.ticker_endpoint)
        rows = flatten_market_rows(data)
        quotes: List[Quote] = []
        wanted = {a.upper() for a in assets}
        for row in rows:
            if not isinstance(row, dict):
                continue
            symbol = str(row.get("symbol") or row.get("code") or row.get("market") or "").upper().replace("-", "_")
            base, quote = split_symbol(symbol)
            if base not in wanted or quote not in {"IRT", "TMN", "TOMAN"}:
                continue
            bid = first_decimal(row, ("best_bid", "highest_bid", "bid", "buy", "buy_price", "bid_price"))
            ask = first_decimal(row, ("best_ask", "lowest_ask", "ask", "sell", "sell_price", "ask_price"))
            if not (bid and ask):
                stats = row.get("stats") if isinstance(row.get("stats"), dict) else {}
                bid = bid or first_decimal(stats, ("best_bid", "bid", "buy", "buy_price"))
                ask = ask or first_decimal(stats, ("best_ask", "ask", "sell", "sell_price"))
            if bid and ask:
                quotes.append(Quote(self.name, base, bid, ask, volume=first_decimal(row, ("volume", "base_volume")), source=self.ticker_endpoint))
        return dedupe_quotes(quotes)

    def _fetch_from_orderbooks(self, assets: Sequence[str]) -> List[Quote]:
        quotes: List[Quote] = []
        for asset in assets:
            symbol = f"{asset.upper()}_IRT"
            try:
                data = http_json(self.orderbook_endpoint.format(symbol=symbol))
                bid, ask = parse_orderbook_bid_ask(data)
                if bid and ask:
                    quotes.append(Quote(self.name, asset.upper(), bid, ask, source=self.orderbook_endpoint.format(symbol=symbol)))
            except MarketDataError:
                continue
        return quotes


class WallexProvider(ExchangeProvider):
    name = "Wallex"
    fee_rate = Decimal("0.0035")
    endpoint = "https://api.wallex.ir/v1/markets"

    def fetch_quotes(self, assets: Sequence[str]) -> List[Quote]:
        data = http_json(self.endpoint)
        rows = flatten_market_rows(data)
        wanted = {a.upper() for a in assets}
        quotes: List[Quote] = []
        for row in rows:
            if not isinstance(row, dict):
                continue
            symbol = str(row.get("symbol") or row.get("name") or row.get("code") or "").upper().replace("-", "")
            base, quote = split_symbol(symbol)
            if base not in wanted or quote not in {"IRT", "TMN", "TOMAN"}:
                continue
            stats = row.get("stats") if isinstance(row.get("stats"), dict) else row
            bid = first_decimal(stats, ("bidPrice", "bid_price", "bestBid", "best_bid", "buyPrice"))
            ask = first_decimal(stats, ("askPrice", "ask_price", "bestAsk", "best_ask", "sellPrice"))
            if bid and ask:
                quotes.append(Quote(self.name, base, bid, ask, volume=first_decimal(stats, ("24h_volume", "volume", "baseVolume")), source=self.endpoint))
        return dedupe_quotes(quotes)


class RamzinexProvider(ExchangeProvider):
    name = "Ramzinex"
    fee_rate = Decimal("0.0035")
    endpoint = "https://publicapi.ramzinex.com/exchange/api/v1.0/exchange/pairs"

    def fetch_quotes(self, assets: Sequence[str]) -> List[Quote]:
        data = http_json(self.endpoint)
        rows = flatten_market_rows(data)
        wanted = {a.upper() for a in assets}
        quotes: List[Quote] = []
        for row in rows:
            if not isinstance(row, dict):
                continue
            symbol = str(row.get("symbol") or row.get("pair") or row.get("name") or "").upper().replace("-", "_")
            base, quote = split_symbol(symbol)
            if base not in wanted or quote not in {"IRT", "TMN", "TOMAN", "IRR"}:
                continue
            bid = first_decimal(row, ("buy", "bid", "best_buy", "bestBid", "buy_price"))
            ask = first_decimal(row, ("sell", "ask", "best_sell", "bestAsk", "sell_price"))
            if quote == "IRR":
                bid = bid / Decimal("10") if bid else None
                ask = ask / Decimal("10") if ask else None
            if bid and ask:
                quotes.append(Quote(self.name, base, bid, ask, volume=first_decimal(row, ("volume", "base_volume")), source=self.endpoint))
        return dedupe_quotes(quotes)


def flatten_market_rows(data: Any) -> List[Any]:
    """استخراج ردیف‌های بازار از چند ساختار رایج پاسخ API.

    ساختار APIهای بازار ثابت و یکسان نیست؛ گاهی لیست بازار داخل result/data و
    گاهی داخل result.markets یا به صورت dict با کلید نماد قرار می‌گیرد.
    """
    rows: List[Any] = []

    def visit(node: Any, inherited_symbol: str = "", depth: int = 0) -> None:
        if depth > 4:
            return
        if isinstance(node, list):
            for item in node:
                visit(item, "", depth + 1)
            return
        if not isinstance(node, dict):
            return

        looks_like_market = any(k in node for k in (
            "symbol", "code", "market", "pair", "name", "best_bid", "best_ask",
            "bid", "ask", "buy", "sell", "stats", "bidPrice", "askPrice",
        ))
        if looks_like_market:
            item = dict(node)
            if inherited_symbol:
                item.setdefault("symbol", inherited_symbol)
            rows.append(item)

        for key in ("results", "result", "data", "markets", "symbols", "pairs", "items"):
            child = node.get(key)
            if child is not None and child is not node:
                visit(child, "", depth + 1)

        if not looks_like_market:
            for key, value in node.items():
                if isinstance(value, dict):
                    visit(value, str(key), depth + 1)
                elif isinstance(value, list) and key not in {"bids", "asks"}:
                    visit(value, "", depth + 1)

    visit(data)
    # حفظ ترتیب و حذف تکراری‌های احتمالی که از مسیرهای مختلف به همان dict رسیده‌اند.
    unique: List[Any] = []
    seen = set()
    for row in rows:
        marker = id(row) if not isinstance(row, dict) else tuple(sorted((str(k), str(v)[:80]) for k, v in row.items()))
        if marker not in seen:
            seen.add(marker)
            unique.append(row)
    return unique


def split_symbol(symbol: str) -> Tuple[str, str]:
    clean = symbol.upper().replace("/", "_").replace("-", "_").replace(" ", "")
    if "_" in clean:
        left, right = clean.split("_", 1)
        return normalize_asset(left), normalize_quote(right)
    for quote in ("TOMAN", "TMN", "IRT", "IRR", "USDT"):
        if clean.endswith(quote) and len(clean) > len(quote):
            return normalize_asset(clean[:-len(quote)]), normalize_quote(quote)
    return normalize_asset(clean), ""


def normalize_asset(asset: str) -> str:
    mapping = {"TETHER": "USDT", "TETH": "USDT", "BITCOIN": "BTC", "ETHER": "ETH", "ETHEREUM": "ETH"}
    return mapping.get(asset.upper(), asset.upper())


def normalize_quote(quote: str) -> str:
    quote = quote.upper()
    return "IRT" if quote in {"TMN", "TOMAN"} else quote


def first_decimal(row: Mapping[str, Any], keys: Iterable[str]) -> Optional[Decimal]:
    for key in keys:
        if key in row:
            number = to_decimal(row.get(key))
            if number is not None:
                return number
    return None


def parse_orderbook_bid_ask(data: Any) -> Tuple[Optional[Decimal], Optional[Decimal]]:
    if isinstance(data, dict):
        for key in ("data", "result", "orderbook", "orders"):
            if isinstance(data.get(key), dict):
                data = data[key]
                break
    if not isinstance(data, dict):
        return None, None
    bids = data.get("bids") or data.get("bid") or data.get("buy") or data.get("buys")
    asks = data.get("asks") or data.get("ask") or data.get("sell") or data.get("sells")
    return best_price_from_levels(bids, is_bid=True), best_price_from_levels(asks, is_bid=False)


def best_price_from_levels(levels: Any, is_bid: bool) -> Optional[Decimal]:
    prices: List[Decimal] = []
    if not isinstance(levels, list):
        return None
    for level in levels:
        value = None
        if isinstance(level, (list, tuple)) and level:
            value = level[0]
        elif isinstance(level, dict):
            value = level.get("price") or level.get("p")
        number = to_decimal(value)
        if number:
            prices.append(number)
    if not prices:
        return None
    return max(prices) if is_bid else min(prices)


def dedupe_quotes(quotes: Iterable[Quote]) -> List[Quote]:
    best: Dict[Tuple[str, str], Quote] = {}
    for quote in quotes:
        key = (quote.exchange, quote.asset)
        old = best.get(key)
        if old is None or quote.timestamp > old.timestamp:
            best[key] = quote
    return list(best.values())


def collect_market_data(providers: Sequence[ExchangeProvider], assets: Sequence[str] = SUPPORTED_ASSETS) -> FetchResult:
    quotes: List[Quote] = []
    errors: Dict[str, str] = {}
    for provider in providers:
        try:
            provider_quotes = provider.fetch_quotes(assets)
            if provider_quotes:
                quotes.extend(provider_quotes)
            else:
                errors[provider.name] = "دادهٔ قابل استفاده برای جفت‌های تومانی پیدا نشد."
        except Exception as exc:  # عمداً همه خطاهای provider جدا ثبت می‌شود تا کل برنامه نخوابد.
            errors[provider.name] = str(exc)[:220]
    return FetchResult(dedupe_quotes(quotes), errors)


def find_opportunities(quotes: Sequence[Quote], fee_rates: Mapping[str, Decimal],
                       slippage_rate: Decimal = Decimal("0.0010"),
                       min_net_percent: Decimal = Decimal("0")) -> List[Opportunity]:
    by_asset: Dict[str, List[Quote]] = {}
    for quote in quotes:
        if quote.bid > 0 and quote.ask > 0 and quote.bid <= quote.ask * Decimal("1.20"):
            by_asset.setdefault(quote.asset, []).append(quote)

    opportunities: List[Opportunity] = []
    for asset, asset_quotes in by_asset.items():
        if len(asset_quotes) < 2:
            continue
        buy = min(asset_quotes, key=lambda q: q.ask)
        sell = max(asset_quotes, key=lambda q: q.bid)
        if buy.exchange == sell.exchange or sell.bid <= buy.ask:
            continue
        gross_profit = sell.bid - buy.ask
        gross_percent = (gross_profit / buy.ask) * Decimal("100")
        estimated_cost_percent = (
            fee_rates.get(buy.exchange, Decimal("0.0035")) +
            fee_rates.get(sell.exchange, Decimal("0.0035")) +
            slippage_rate
        ) * Decimal("100")
        net_percent = gross_percent - estimated_cost_percent
        net_profit = buy.ask * net_percent / Decimal("100")
        if net_percent < min_net_percent:
            continue
        risk_note = classify_risk(net_percent, gross_percent, estimated_cost_percent)
        opportunities.append(Opportunity(
            asset=asset,
            buy_exchange=buy.exchange,
            sell_exchange=sell.exchange,
            buy_price=buy.ask,
            sell_price=sell.bid,
            gross_profit=gross_profit,
            gross_percent=gross_percent,
            estimated_cost_percent=estimated_cost_percent,
            net_profit=net_profit,
            net_percent=net_percent,
            risk_note=risk_note,
        ))
    opportunities.sort(key=lambda item: item.net_percent, reverse=True)
    return opportunities


def classify_risk(net_percent: Decimal, gross_percent: Decimal, cost_percent: Decimal) -> str:
    if net_percent >= Decimal("1.2"):
        return "جذاب، اما قبل از معامله عمق سفارش و محدودیت برداشت بررسی شود."
    if net_percent >= Decimal("0.3"):
        return "مرزی؛ فقط با حجم کم و تأیید عمق بازار قابل بررسی است."
    if gross_percent > 0 and net_percent <= 0:
        return "اختلاف خام مثبت است ولی پس از کارمزد/لغزش سود ندارد."
    return "پرریسک یا بسیار کم‌سود."


def default_providers() -> List[ExchangeProvider]:
    return [NobitexProvider(), BitpinProvider(), WallexProvider(), RamzinexProvider()]


def default_fee_rates(providers: Optional[Sequence[ExchangeProvider]] = None) -> Dict[str, Decimal]:
    providers = providers or default_providers()
    return {provider.name: provider.fee_rate for provider in providers}


def timestamp_text(ts: Optional[float] = None) -> str:
    dt = datetime.fromtimestamp(ts or time.time(), tz=timezone.utc).astimezone()
    return dt.strftime("%Y-%m-%d %H:%M:%S")
