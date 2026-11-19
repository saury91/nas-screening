# -*- coding: utf-8 -*-
"""服务端行为测试：缓存失效、写接口跨站防护、快照、探活只读。

这些是「改坏了也不会立刻报错」的地方 —— 缓存清不掉只是数据变旧，
CSRF 防护漏了没有任何界面表现，所以要靠测试钉住。
"""
import json
import os
import sqlite3
import tempfile
import unittest
from unittest import mock

from server import cache
from server.api import Handler
from server.services import backup


class TestCacheDrop(unittest.TestCase):
    """cache_drop 必须按写入时的 schema 前缀匹配，否则等于没清。"""

    def setUp(self):
        cache._CACHE.clear()
        self._patch = mock.patch.object(cache, "dbrun", lambda *a, **k: None)
        self._patch.start()

    def tearDown(self):
        self._patch.stop()
        cache._CACHE.clear()

    def test_drop_by_prefix(self):
        cache.cache_put("detail|v5|沙丘", {"sources": []})
        cache.cache_put("detail|v5|流浪地球", {"sources": []})
        cache.cache_put("browse|movie|1", {"items": []})
        self.assertIsNotNone(cache.cache_peek("detail|v5|沙丘"))

        cache.cache_drop("detail|")
        self.assertIsNone(cache.cache_peek("detail|v5|沙丘"))
        self.assertIsNone(cache.cache_peek("detail|v5|流浪地球"))
        self.assertIsNotNone(cache.cache_peek("browse|movie|1"))

    def test_drop_one_site(self):
        cache.cache_put("catmap3|源A", {"电影": "1"})
        cache.cache_put("catmap3|源B", {"电影": "2"})
        cache.cache_drop("catmap3|源A")
        self.assertIsNone(cache.cache_peek("catmap3|源A"))
        self.assertIsNotNone(cache.cache_peek("catmap3|源B"))

    def test_drop_all(self):
        cache.cache_put("x|1", 1)
        cache.cache_put("y|2", 2)
        cache.cache_drop()
        self.assertIsNone(cache.cache_peek("x|1"))
        self.assertIsNone(cache.cache_peek("y|2"))


class _FakeHandler:
    """只提供 _write_guard 需要的两个属性。"""

    def __init__(self, headers, cors_origin):
        self.headers = headers
        self._cors_origin = lambda: cors_origin


class TestWriteGuard(unittest.TestCase):
    """写接口只收 JSON + 同源，挡住局域网里的跨站表单提交。"""

    def guard(self, headers, cors_origin=None):
        return Handler._write_guard(_FakeHandler(headers, cors_origin))

    def test_plain_json_allowed(self):
        self.assertEqual(self.guard({"Content-Type": "application/json"}), "")

    def test_json_with_charset_allowed(self):
        self.assertEqual(
            self.guard({"Content-Type": "application/json; charset=utf-8"}), "")

    def test_curl_without_origin_allowed(self):
        # 命令行、App 都不发 Origin，不该被拦
        self.assertEqual(self.guard({"Content-Type": "application/json"}), "")

    def test_form_post_rejected(self):
        self.assertTrue(self.guard(
            {"Content-Type": "application/x-www-form-urlencoded"}))

    def test_missing_content_type_rejected(self):
        self.assertTrue(self.guard({}))

    def test_cross_site_origin_rejected(self):
        self.assertTrue(self.guard(
            {"Content-Type": "application/json",
             "Origin": "http://evil.example"}, cors_origin=None))

    def test_same_origin_allowed(self):
        self.assertEqual(self.guard(
            {"Content-Type": "application/json",
             "Origin": "http://192.168.1.9:8899"},
            cors_origin="http://192.168.1.9:8899"), "")

    def test_sec_fetch_site_rejected(self):
        self.assertTrue(self.guard(
            {"Content-Type": "application/json", "Sec-Fetch-Site": "cross-site"}))


class TestTestSource(unittest.TestCase):
    """GET 探活只读：不该因为一次 GET 就把状态写进库。"""

    def _run(self, record):
        from server.providers import test_source
        with mock.patch("server.providers.cms.fetch_vod", return_value=[{"vod_id": 1}]) as f, \
                mock.patch("server.providers.dbrun") as w:
            out = test_source({"id": 7, "name": "源"}, record=record)
        self.assertTrue(out["ok"])
        return w

    def test_record_false_never_writes(self):
        self.assertFalse(self._run(False).called)

    def test_record_true_writes_status(self):
        self.assertTrue(self._run(True).called)


class TestBackup(unittest.TestCase):
    """快照要能真的读回来，且只保留 BACKUP_KEEP 份。"""

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.db = os.path.join(self.tmp, "media.db")
        con = sqlite3.connect(self.db)
        con.execute("CREATE TABLE t(x)")
        con.execute("INSERT INTO t VALUES (42)")
        con.commit()
        con.close()
        self._p1 = mock.patch.object(backup, "DB_PATH", self.db)
        self._p2 = mock.patch.object(backup, "BACKUP_DIR",
                                     os.path.join(self.tmp, "backups"))
        self._p3 = mock.patch.object(backup, "BACKUP_KEEP", 2)
        self._p1.start()
        self._p2.start()
        self._p3.start()

    def tearDown(self):
        self._p1.stop()
        self._p2.stop()
        self._p3.stop()

    def test_snapshot_readable(self):
        path = backup.snapshot()
        self.assertTrue(path and os.path.exists(path))
        con = sqlite3.connect(path)
        self.assertEqual(con.execute("SELECT x FROM t").fetchone()[0], 42)
        con.close()

    def test_snapshot_missing_db(self):
        with mock.patch.object(backup, "DB_PATH", os.path.join(self.tmp, "nope.db")):
            self.assertEqual(backup.snapshot(), "")


if __name__ == "__main__":
    unittest.main()
