from decimal import Decimal
import unittest

from arbitrage_core import (
    Quote,
    find_opportunities,
    flatten_market_rows,
    parse_orderbook_bid_ask,
    split_symbol,
)


class ArbitrageCoreTest(unittest.TestCase):
    def test_find_opportunity_after_costs(self):
        quotes = [
            Quote('Nobitex', 'BTC', Decimal('100'), Decimal('101')),
            Quote('Bitpin', 'BTC', Decimal('105'), Decimal('106')),
        ]
        opportunities = find_opportunities(
            quotes,
            {'Nobitex': Decimal('0.001'), 'Bitpin': Decimal('0.001')},
            slippage_rate=Decimal('0.001'),
            min_net_percent=Decimal('0'),
        )
        self.assertEqual(len(opportunities), 1)
        self.assertEqual(opportunities[0].buy_exchange, 'Nobitex')
        self.assertEqual(opportunities[0].sell_exchange, 'Bitpin')
        self.assertGreater(opportunities[0].net_percent, Decimal('3'))

    def test_no_opportunity_same_exchange(self):
        quotes = [Quote('Nobitex', 'ETH', Decimal('100'), Decimal('101'))]
        self.assertEqual(find_opportunities(quotes, {'Nobitex': Decimal('0.001')}), [])

    def test_symbol_parser(self):
        self.assertEqual(split_symbol('BTCIRT'), ('BTC', 'IRT'))
        self.assertEqual(split_symbol('USDT_IRT'), ('USDT', 'IRT'))
        self.assertEqual(split_symbol('eth-toman'), ('ETH', 'IRT'))

    def test_orderbook_parser(self):
        bid, ask = parse_orderbook_bid_ask({'data': {'bids': [['99', '2']], 'asks': [['101', '2'], ['100', '1']]}})
        self.assertEqual(bid, Decimal('99'))
        self.assertEqual(ask, Decimal('100'))

    def test_flatten_nested_market_response(self):
        data = {'result': {'markets': [{'symbol': 'BTCIRT', 'stats': {'bidPrice': '1', 'askPrice': '2'}}]}}
        rows = flatten_market_rows(data)
        self.assertTrue(rows)
        self.assertEqual(rows[0]['symbol'], 'BTCIRT')


if __name__ == '__main__':
    unittest.main()
