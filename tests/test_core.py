# -*- coding: utf-8 -*-
"""纯函数回归测试：跑 `python3 -m unittest discover -s tests -v`。

这些是最容易被"优化"改坏的解析逻辑：片名归一、播放地址容错、
TVBox 脏 JSON、直播源两种格式、画质识别、FLAC 时长。
"""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# 测试只写临时库：必须在 import server.* 之前设置（config 在导入时读 DB_PATH），
# 否则 pref_write 之类的用例会写进真实的 data/media.db
os.environ.setdefault("DB_PATH", os.path.join(tempfile.mkdtemp(prefix="proom-test-"), "t.db"))

from server.text import (_clean_desc, _norm_key, _relevance, _score_max,  # noqa: E402
                         _zh_count, guess_quality)
from server.providers.cms import _parse_play_url  # noqa: E402
from server.providers import tvbox  # noqa: E402
from server.services.live import parse_live  # noqa: E402
from server.services.music import _flac_duration  # noqa: E402


class TestText(unittest.TestCase):
    def test_norm_key(self):
        self.assertEqual(_norm_key("沙丘：第二部"), "沙丘第二部")
        self.assertEqual(_norm_key("  Oppenheimer (2023) "), "oppenheimer")
        self.assertEqual(_norm_key("沙丘 1984"), "沙丘1984")

    def test_relevance(self):
        self.assertEqual(_relevance("沙丘", "沙丘"), 100)
        self.assertGreater(_relevance("沙丘2", "沙丘"), _relevance("外传沙丘", "沙丘"))

    def test_clean_desc(self):
        self.assertEqual(_clean_desc("<p>你好&nbsp;世界</p>"), "你好 世界")

    def test_zh_count(self):
        self.assertEqual(_zh_count("abc中文"), 2)

    def test_score_max(self):
        self.assertEqual(_score_max("7.2", "8.4"), "8.4")
        self.assertEqual(_score_max("", "8.4"), "8.4")
        self.assertEqual(_score_max("0", ""), "")

    def test_guess_quality(self):
        self.assertEqual(guess_quality("1080P 中字"), "1080P")
        self.assertEqual(guess_quality("HD中字"), "HD")
        self.assertEqual(guess_quality("https://a.com/2024/1080/index.m3u8"), "1080P")
        # 域名里的 sd 不能被当成 480P
        self.assertEqual(guess_quality("https://sd.example/x.m3u8"), "")
        self.assertEqual(guess_quality(""), "")


class TestPlayUrl(unittest.TestCase):
    def test_standard(self):
        eps = _parse_play_url("第01集$https://a/x.m3u8#第02集$https://a/y.m3u8")
        self.assertEqual([e["name"] for e in eps], ["第01集", "第02集"])
        self.assertEqual(eps[0]["url"], "https://a/x.m3u8")

    def test_share_page_prefix(self):
        # 非凡/电影天堂：前面挂了一个 share 页地址
        eps = _parse_play_url("https://share/x$$$HD中字$https://b/index.m3u8")
        self.assertEqual(len(eps), 1)
        self.assertEqual(eps[0]["url"], "https://b/index.m3u8")
        self.assertEqual(eps[0]["name"], "HD中字")

    def test_two_groups_stuck_together(self):
        seg = "第30集$https://a/x$$$第01集$https://a/90153_index.m3u8"
        eps = _parse_play_url(seg)
        self.assertEqual(eps[0]["name"], "第01集")

    def test_out_of_order(self):
        eps = _parse_play_url("第3集$https://a/3.m3u8#第1集$https://a/1.m3u8#第2集$https://a/2.m3u8")
        self.assertEqual([e["name"] for e in eps], ["第1集", "第2集", "第3集"])

    def test_skip_non_video(self):
        self.assertEqual(_parse_play_url("第1集$https://a/page.html"), [])
        self.assertEqual(_parse_play_url(""), [])


class TestTvbox(unittest.TestCase):
    def test_clean_json(self):
        raw = '\ufeff// 注释\n{"a":"x\ny"}'
        cleaned = tvbox.clean_json(raw)
        self.assertNotIn("\ufeff", cleaned)
        self.assertNotIn("// 注释", cleaned)
        # 字符串内的裸换行被转义，json 仍可解析
        import json
        self.assertEqual(json.loads(cleaned)["a"], "x\ny")

    def test_multi_urls(self):
        text = '{"urls":[{"name":"仓A","url":"https://a/x.json"},"https://b/y.json"]}'
        self.assertEqual(tvbox.multi_urls(text),
                         [("仓A", "https://a/x.json"), ("", "https://b/y.json")])

    def test_parse_config_skips(self):
        import json
        cfg = {"sites": [
            {"name": "好的", "api": "https://ok/api.php/provide/vod/", "type": 1},
            {"name": "jar源", "api": "https://x/api", "type": 3},
            {"name": "本地", "api": "http://127.0.0.1:9978/proxy?do=vod"},
            {"name": "不可搜", "api": "https://y/api", "type": 1, "searchable": 0},
        ]}
        sites, lives, skipped, _ = tvbox.parse_config(json.dumps(cfg))
        self.assertEqual([s["name"] for s in sites], ["好的"])
        self.assertEqual(len(skipped), 3)
        self.assertTrue(all("reason" in s for s in skipped))

    def test_infer_type(self):
        self.assertEqual(tvbox.infer_type("https://a/api.php/provide/vod/"), 1)
        self.assertEqual(tvbox.infer_type("https://a/x.xml"), 0)
        self.assertEqual(tvbox.infer_type("https://a/api", 0), 0)


class TestLive(unittest.TestCase):
    def test_txt(self):
        text = "央视,#genre#\nCCTV1,http://a/1.m3u8\nCCTV2,http://a/2.m3u8\n"
        chans = parse_live(text)
        self.assertEqual(len(chans), 2)
        self.assertEqual(chans[0]["group"], "央视")
        self.assertEqual(chans[0]["name"], "CCTV1")

    def test_m3u(self):
        text = ('#EXTM3U\n#EXTINF:-1 group-title="News;HD",CNN\nhttp://a/cnn.m3u8\n')
        chans = parse_live(text)
        self.assertEqual(chans[0]["group"], "新闻")
        self.assertEqual(chans[0]["url"], "http://a/cnn.m3u8")

    def test_placeholder_filtered(self):
        text = "更新,#genre#\n更新时间: 2026,http://127.0.0.1/x.m3u8\n正常,http://a/1.m3u8\n"
        self.assertEqual([c["name"] for c in parse_live(text)], ["正常"])


class TestFlac(unittest.TestCase):
    def _write_flac(self, rate, total, channels=2, bps=16):
        bits = (rate << 44) | ((channels - 1) << 41) | ((bps - 1) << 36) | total
        streaminfo = (b"\x10\x00" + b"\x10\x00" + b"\x00\x00\x00" +
                      b"\x00\x00\x00" + bits.to_bytes(8, "big") + b"\x00" * 16)
        streaminfo = streaminfo[:34].ljust(34, b"\x00")
        header = b"fLaC" + bytes([0x00]) + len(streaminfo).to_bytes(3, "big")
        fd, path = tempfile.mkstemp(suffix=".flac")
        with os.fdopen(fd, "wb") as f:
            f.write(header + streaminfo)
        return path

    def test_duration(self):
        path = self._write_flac(44100, 44100 * 10)
        try:
            self.assertEqual(_flac_duration(path), 10)
        finally:
            os.remove(path)

    def test_bad_file(self):
        fd, path = tempfile.mkstemp(suffix=".flac")
        os.write(fd, b"not a flac at all")
        os.close(fd)
        try:
            self.assertEqual(_flac_duration(path), 0)
        finally:
            os.remove(path)


class TestMusicBox(unittest.TestCase):
    """歌单推荐的纯逻辑：标题过滤、品类归并、曲目归一。"""

    def test_blocked_titles(self):
        from server.services import music as m
        self.assertTrue(m._blocked("【微醺】包有氛围的歌单"))
        self.assertFalse(m._blocked("纯音乐写作业/看书轻音乐"))

    def test_keyword_pick(self):
        from server.services import music as m
        # 命中就归并到第一个命中的偏好词，用来限制同类货架数量
        self.assertEqual(m._keyword({"name": "雨夜·钢琴纯音乐"}), "纯音乐")
        self.assertEqual(m._keyword({"name": "Chill Time 一杯暖咖"}), "chill")
        self.assertEqual(m._keyword({"name": "随便什么名字"}), "")

    def test_track_shape(self):
        from server.services import musicbox as mb
        t = mb._track({"id": 123, "name": "夜曲", "artist": "某", "pic": "http://x/1.jpg"})
        self.assertEqual(t["provider"], "netease")
        self.assertEqual(t["url"], "/api/music/online/stream?id=123")
        self.assertIsNone(mb._track({"name": "无 id"}))

    def test_stream_target_guard(self):
        from server.services import musicbox as mb
        self.assertEqual(mb.stream_target("abc"), "")

    def test_stream_target_needs_base(self):
        """没配在线音源时一律返回空串，绝不拼出半截地址去打上游。"""
        from server.services import musicbox as mb
        self.assertFalse(mb.configured())
        self.assertEqual(mb.stream_target("123"), "")
        self.assertEqual(mb.lrc_url("123"), "")
        self.assertEqual(mb.recommend(), [])
        self.assertEqual(mb.categories(), [])
        self.assertEqual(mb.playlist("1")["tracks"], [])

    def test_stream_target_with_base(self):
        """配了地址才拼目标 URL；用 pref 覆盖，不依赖环境变量。"""
        from server.db import pref_write
        from server.services import musicbox as mb
        pref_write("music.api", "https://example.invalid")
        try:
            self.assertTrue(mb.configured())
            self.assertIn("type=url", mb.stream_target("123"))
            self.assertIn("type=lrc", mb.lrc_url("123"))
        finally:
            pref_write("music.api", "")

    def test_full_threshold(self):
        # 试听片段约 480KB，整曲至少 1.2MB：阈值两侧各判一次
        from server.services import musicbox as mb
        mb.probe_size = lambda sid, timeout=12: 481115
        self.assertFalse(mb.is_full("1"))
        mb.probe_size = lambda sid, timeout=12: 4130525
        self.assertTrue(mb.is_full("2"))


class TestLyrics(unittest.TestCase):
    """歌词解析：时间戳、多行同刻、毫秒位数。"""

    def test_parse_basic(self):
        from server.services.music import parse_lrc
        L = parse_lrc("[00:05.00]第一行\n[01:02.50]第二行")
        self.assertEqual([x["text"] for x in L], ["第一行", "第二行"])
        self.assertAlmostEqual(L[1]["t"], 62.5, places=2)

    def test_multi_stamp_and_translation(self):
        from server.services.music import parse_lrc
        L = parse_lrc("[00:10.00][00:30.00]副歌\n[00:10.00]chorus")
        self.assertEqual(len(L), 2)
        self.assertIn("chorus", L[0]["text"])

    def test_skip_bad_lines(self):
        from server.services.music import parse_lrc
        self.assertEqual(parse_lrc("没有时间戳\n[00:01.00]"), [])
        self.assertEqual(parse_lrc(""), [])

    def test_norm_title(self):
        from server.services.music import _norm_title
        self.assertEqual(_norm_title("孤勇者 (童声版)"), "孤勇者")
        self.assertEqual(_norm_title("晴天（Live）"), "晴天")
        self.assertEqual(_norm_title("A-B_C"), "abc")


class TestConfigIO(unittest.TestCase):
    """配置包：版本兼容与清洗（纯函数，不碰数据库）。"""

    def test_accepts_own_export(self):
        from server.services import config_io as C
        doc = {"kind": C.KIND, "schema": C.SCHEMA, "sections": {
            "sources": [{"name": "A", "url": "https://a.com/x", "pri": 2}],
            "liveChannels": [{"grp": "g", "name": "CCTV-1", "url": "http://x/y.m3u8"}],
            "prefs": {"live.seeds": ["https://seed"]}}}
        data, meta, warns = C.normalize(doc)
        self.assertEqual(len(data["sources"]), 1)
        self.assertEqual(data["sources"][0]["pri"], 2)
        self.assertEqual(meta["fromSchema"], C.SCHEMA)
        self.assertEqual(warns, [])

    def test_rejects_future_schema(self):
        from server.services import config_io as C
        with self.assertRaises(C.ConfigError) as ctx:
            C.normalize({"kind": C.KIND, "schema": C.SCHEMA + 99, "sections": {}})
        self.assertEqual(ctx.exception.code, 409)

    def test_legacy_flat_without_kind(self):
        from server.services import config_io as C
        data, meta, warns = C.normalize(
            {"sources": [{"name": "老源", "url": "https://a.com/api"}]})
        self.assertEqual(len(data["sources"]), 1)
        self.assertTrue(any("kind" in w for w in warns))

    def test_drops_bad_items_keeps_good(self):
        from server.services import config_io as C
        data, _, warns = C.normalize({"kind": C.KIND, "schema": 1, "sections": {
            "sources": [
                {"name": "ok", "url": "https://a.com/x"},
                {"name": "", "url": "https://b.com"},
                {"name": "ftp", "url": "ftp://c.com"},
                "junk"]}})
        self.assertEqual([x["name"] for x in data["sources"]], ["ok"])
        self.assertEqual(len(warns), 3)

    def test_prefs_filter_secrets(self):
        from server.services import config_io as C
        data, _, _ = C.normalize({"kind": C.KIND, "schema": 1, "sections": {"prefs": {
            "live.seeds": ["x"], "music.provider": "local",
            "qbt.password": "hunter2", "cms.discover.result": {"a": 1}}}})
        self.assertIn("live.seeds", data["prefs"])
        self.assertIn("music.provider", data["prefs"])
        self.assertNotIn("qbt.password", data["prefs"])
        self.assertNotIn("cms.discover.result", data["prefs"])

    def test_bad_json_message(self):
        from server.services import config_io as C
        with self.assertRaises(C.ConfigError):
            C.normalize("{not json")


class TestAuth(unittest.TestCase):
    """PIN 只做哈希与校验（不落库）。"""

    def test_hash_and_verify(self):
        from server.services import auth
        h = auth.hash_pin("2468")
        self.assertTrue(h.startswith("pbkdf2$"))
        self.assertNotIn("2468", h)          # 不能存明文
        self.assertTrue(auth.verify_pin("2468", h))
        self.assertFalse(auth.verify_pin("2469", h))
        self.assertFalse(auth.verify_pin("", h))

    def test_empty_pin_means_open(self):
        from server.services import auth
        self.assertTrue(auth.verify_pin("", ""))
        self.assertTrue(auth.verify_pin("whatever", ""))

    def test_bad_stored_value(self):
        from server.services import auth
        self.assertFalse(auth.verify_pin("1234", "garbage"))

    def test_pin_format(self):
        from server.services import auth
        for ok in ("123456", "000000", "987654"):
            self.assertTrue(auth.PIN_RE.match(ok), ok)
        for bad in ("12345", "1234567", "adnas", "12 345", ""):
            self.assertFalse(auth.PIN_RE.match(bad), bad)

    def test_default_admin_credential(self):
        from server.services import auth
        self.assertEqual(auth.DEFAULT_ADMIN_NAME, "admin")
        self.assertEqual(auth.DEFAULT_ADMIN_PIN, "123456")
        self.assertTrue(auth.PIN_RE.match(auth.DEFAULT_ADMIN_PIN))


if __name__ == "__main__":
    unittest.main()
