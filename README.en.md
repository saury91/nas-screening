# Private Screening

[中文](README.md) · **English**

Version **0.15.0** · One codebase for Web / Android phone / Android TV

> **An empty-shell player.** No sources, no channels, no music endpoints, no bundled configuration.
> The repository contains no media files and no content addresses. A blank first launch is by design,
> not a failure. Every content source is found and configured by the user.

---

## Contents

- [1. Scope](#1-scope)
[1. Scope](#1-scope) · [2. Feature list](#2-feature-list) · [3. Client matrix](#3-client-matrix) ·
[4. Architecture](#4-architecture) · [5. Screenshots](#5-screenshots) · [6. Deployment](#6-deployment) ·
[7. First run](#7-first-run) · [8. Environment variables](#8-environment-variables) ·
[9. Project layout](#9-project-layout) · [10. Development and tests](#10-development-and-tests) ·
[11. License and content notice](#11-license-and-content-notice)

---

## 1. Scope

| Item | Description |
|---|---|
| What it is | A unified player frontend for films, live TV and music, plus a thin backend limited to config distribution and self-update |
| What it is not | A content site: it does not scrape, store or distribute any work, and ships no subscriptions |
| Content sources | Scraping-site APIs, channel lists and music services supplied by the user |
| Bundled config | None. The config bundle is empty; no connection details are built in |
| Nature | Personal project published as-is; non-commercial, no operations or support commitment |
| Runtime | Build-free frontend: `python3 app.py`, no pip / Node / bundling |
| Offline | With the NAS off, clients still search, browse and play using the last synced config |

---

## 2. Feature list

### 2.1 Films

| Capability | What it does |
|---|---|
| Aggregated search | Asks every source at once, merges the same title, lists all of its streams |
| Multi-stream playback | Each stream is tagged with its quality (4K / 1080P / 720P / HD); switch when one stalls |
| Series | Episodes in order, progress preserved across stream changes, auto-advance |
| Library browsing | Movies / series / anime / variety / documentary, filtered by year, region or source |
| Source management | Import a TVBox config URL in one tap, reorder by priority, test individually, toggle (Web) |
| Downloads | Your own qBittorrent; magnet results show seeders, one tap to submit (Web) |

### 2.2 Live TV

| Capability | What it does |
|---|---|
| Import and grouping | M3U / TXT import, auto-grouped, same-name channels deduped |
| Health check | One tap to probe; dead channels are dimmed and kept, never removed automatically |
| Daily refresh | Fetches candidate sources daily, merges and health-checks them, keeps what plays |
| Maintenance | Grouped view, manual refresh, batch health check (Web) |

### 2.3 Music

| Capability | What it does |
|---|---|
| Library | Local FLAC/ID3, Subsonic-compatible servers (Navidrome / Airsonic / Gonic), online playlists |
| Discovery | Recommended and category playlists (theme · language · style · scene · mood), albums, artists, online search |
| Playback | Queue, repeat modes, scrolling lyrics, waveform progress, remembered playback mode |
| DSP | Web: 10-band EQ with 8 presets; TV presets adapt to the device's own frequency bands |
| Per client | Phone: spinning record with line-by-line lyrics, persistent mini player. TV: record left, lyrics right, D-pad changes track and seeks |

### 2.4 Player

| Capability | Notes |
|---|---|
| Gestures and remote | Tap to pause/play, double-tap for fullscreen; the TV uses D-pad focus, OK, BACK |
| Resume | Progress recorded per second; playback resumes from the last position |
| Buffering state | Blurred poster, bouncing dots and live bitrate; no spinner |
| Picture-in-picture | Browse, search and switch titles in a small window; closing it ends playback |
| Subtitles | App: switch embedded tracks and attach local SRT / VTT / ASS / TTML, remembered per title + episode, four size steps. Web: external files only, embedded tracks are not parsed |
| Audio tracks | App: manual selection between multiple tracks; defaults to automatic |

### 2.5 Casting

The phone hands a title to the TV and turns into the remote. The TV is a receiver: it plays in place and
reports real progress rather than mirroring the phone's screen. Transports are chosen automatically and
unsupported keys are greyed out:

| Transport | How it connects | What it can do |
|---|---|---|
| NAS relay | Both ends connect to the same NAS | Everything: speed, volume, progress read-back; works across subnets |
| LAN peer-to-peer | Auto-discovered on the same LAN | Everything, without the NAS — works with the NAS off |
| DLNA | The TV acts as a standard DLNA renderer | Play / pause / seek / stop only; no progress read-back |

If LAN discovery is blocked by the router, pair via the QR code and PIN shown on the TV, or enter the address manually.

### 2.6 Admin and backup

| Capability | Notes |
|---|---|
| Config backup | Export everything or sources only; import shows a preview, backs up first, can roll back |
| Config sharing | Sources, live feeds, music and UI preferences in one JSON — exportable and importable |
| Config distribution | The NAS only ships a config bundle; apps refresh it in the background without redrawing |
| Download tasks | Pause / resume / delete, auto-refreshing status |
| About and self-check | Backend reachability, source availability, version info |
| Self-update | On launch the app checks for a new build and hands it to the system installer |

### 2.7 Independence

| Capability | Notes |
|---|---|
| Offline use | Search, browse and play with the NAS off, using the last synced config |
| Local first | Page data and title index live on device; render first, refresh after |
| Zero third-party deps | No build step, self-hosted fonts, no CDN references |

Mechanisms are described in [4.3](#43-standalone-operation).

---

## 3. Client matrix

| | Desktop web | Phone app | TV app |
|---|---|---|---|
| Package | — (open in a browser) | `com.projectionroom.app` | `com.projectionroom.tv` |
| Role | Admin console + player | Pocket player | Big-screen player |
| Input | Keyboard and mouse | Touch | D-pad remote |
| Browse / search / play | ✅ | ✅ | ✅ |
| Favorites and history | ✅ | ✅ | ✅ |
| Music | ✅ full | ✅ full | ✅ full (with EQ presets) |
| Casting | Receiver controls | ✅ sender | Receiver |
| Sources / live feeds | ✅ everywhere | ✅ subscription sources + config import/export | ✅ same as phone |
| Downloads / scheduled backup | ✅ everywhere | ❌ not offered | ❌ not offered |

| Client | Constraints |
|---|---|
| Desktop | `http://<nas>:8899` is always the latest build; adaptive layout from 375 to 3840 |
| Phone | Four tabs: Films / Live / Music / Mine; configuration is limited to subscription sources and config import/export — source probing, downloads and scheduled backup stay on the desktop console |
| TV | BACK is the only way back, no focusable back button; initial focus on the first actionable item; no lock screen, PiP or casting entry; no system file picker, so local subtitles must be picked on the phone |

---

## 4. Architecture

The web app runs on the NAS, the apps run on the devices; they share protocol and configuration, not a runtime.

### 4.1 Layers and dependency direction

```
PC browser                Phone app                    TV app
static/app/               ui-phone + app-phone         ui-tv + app-tv
zero-build ES modules           │                            │
     │                    ┌─────┴────────────────────────────┴─────┐
     │                    │ ui-media  player / music / live (shared) │
     │                    │ data      storage / sync / pairing / DLNA│
     │                    │ core      pure Kotlin: parse / merge / UI│
     │                    └─────────────────────────────────────────┘
server/ (Python standard library, no framework, no pip)
```

| Layer | Location | Responsibility | Constraint |
|---|---|---|---|
| Protocol | `native/core` | MacCMS parsing, cross-site merge, title normalisation, quality buckets, cast payloads | Pure Kotlin, no Android deps, thickest test coverage |
| Platform | `native/data` | Storage, config bundle, NAS gate, favourite sync, discovery, DLNA | Depends on `core` only |
| Visual kit | `native/ui-kit` | Theme, icons, skeletons | No `if (isTv)` allowed |
| Shared UI | `native/ui-media` | Player, music library, live library | Shared, differences injected |
| Per-client UI | `ui-phone` / `ui-tv` | Gestures vs 10-foot focus ring | No cross references |
| Web | `static/app` + `server/` | Same `/api/*` semantics as the apps | Zero build, edit and refresh |

### 4.2 Scope of the NAS

| Responsibility | Endpoints | Why it must be on the NAS |
|---|---|---|
| Sync | `/api/fav` `/api/history` `/api/library` | Every device shares one set of favourites and progress |
| Cast | `/api/cast/*` | Relay when phone and TV are on different subnets |
| Update | `/api/app/latest` `/api/app/download` | Version check and APK delivery |
| Config | `/api/config/bundle` | A source change takes effect on all three clients |

Everything else is resource loading (home, library, search, detail, streams, playback, covers, music)
and goes from the client straight to the source. Aggregating it on the NAS would make the NAS the
bottleneck and make every client depend on its uptime.

The one exception is the web HLS proxy `/api/proxy`: browser CORS and Referer rules cannot be bypassed,
so a same-origin relay is required; private destinations are refused by default (`PROXY_ALLOW_PRIVATE=0`).

### 4.3 Standalone operation

All three mechanisms are required; missing one degrades the client to a NAS-dependent shell:

| Mechanism | Implementation | Effect |
|---|---|---|
| Config on device | Atomic write (`.bak` → `.tmp` → rename), fall back to `.bak` | Usable indefinitely after one sync |
| Cache never expires | No TTL; freshness only decides whether to refresh in the background | Home still has content while the NAS is offline |
| Local primary store | Favourites and progress written locally first, pushed when the NAS is online | No loss during long offline periods; reconciled on reconnect |

The home feed is assembled locally as well: first page of each of five categories, merged, deduplicated
and sorted by year. The snapshot keeps display fields only (`lines` dropped, 1.8 MB → 53 KB) and reaches
first paint in 1.4 s; the NAS is queried only when local assembly fails.

### 4.4 Primary store and sync

| Data | Primary | How it syncs | Constraint |
|---|---|---|---|
| Sources / live / music config | NAS | Compare version, rewrite only on change | Seeding, manual sync and restore-built-in all go through one `ConfigStore.save`, with no second write path |
| Favourites | **Device** | Write local (`pendingFav=true`) → push → clear flag | The NAS side is a toggle: pull the current state first and skip when equal, otherwise "unfavourite" inverts into "favourite again" |
| Playback history | **Device** | Same, keyed by title + episode | Progress written per second; last write wins |
| Home feed | Device | Snapshot rebuilt in the background after 15 min | The NAS is queried only when local assembly fails |
| Cast progress | TV | TV reports back to the phone | Only the NAS relay and LAN direct channels report; DLNA does not |

Two constraints:

1. **Sync endpoints skip the disk cache**: judging consistency from a cached snapshot breaks the toggle semantics;
2. **Failures trip a breaker**: after one failure `NasGate` stops retrying for 60 s; config pull is capped at 8 s and favourite sync at 6 s, both background only and never blocking the UI.

### 4.5 Discovery and pairing

```
Phone first run ──► UDP broadcast PROOM_DISCOVER to 8897 ──► NAS discovery_loop replies PROOM_OFFER
                                                                └─ address filled in automatically
Phone ⇄ TV ──► UDP on the same LAN (LAN direct, never through the NAS)
               └─ if the router blocks it: the TV shows a QR code plus a pairing code
```

The pairing is written to local settings; later launches do not require the NAS.

### 4.6 Design trade-offs

| Decision | Common approach | Reason |
|---|---|---|
| Two separate apps | One app with `if (isTv)` | Focus-ring and gesture constraints conflict; a change to one side tends to break the other |
| KMP with native UI | WebView shell | Sources do not send CORS headers to `127.0.0.1`; a shell requires either full proxying (NAS becomes the bottleneck) or per-source adaptation. Native also provides hardware decoding and background playback |
| Zero-build frontend | Vite / React | No Node toolchain on a NAS; edit and refresh is sufficient |
| Four NAS responsibilities | Proxy all traffic | Server-side aggregation becomes the bottleneck, and a shutdown disables every client |

---

## 5. Screenshots

Screenshots show interface structure, configuration and pairing only; they contain no source data or
third-party title information.

Config backup and sharing (preview before import, backup written first):

![Config backup](docs/screenshot-backup.png)

TV pairing by QR code (direct connection when LAN discovery is blocked):

<p>
<img src="docs/screenshot-app-tv-pair.png" width="560" alt="TV pairing">
</p>

---

## 6. Deployment

### 6.1 Run directly

```bash
git clone https://github.com/saury91/nas-screening.git
cd nas-screening
python3 app.py
```

Open `http://127.0.0.1:8899`. The database is created on first run; home and the five category pre-warms take about half a minute in the background, so opening a category right away waits a few seconds, after which switching tabs is immediate.

### 6.2 Docker

```bash
docker build -t projection-room .

docker run -d --name projection-room --restart unless-stopped --network host \
  -e DB_PATH=/data/media.db \
  -v /your/data/dir:/data \
  projection-room
```

With qBittorrent and a local music folder:

```bash
docker run -d --name projection-room --restart unless-stopped --network host \
  -e DB_PATH=/data/media.db \
  -e QBT_URL=http://127.0.0.1:8080 \
  -e QBT_USER=<user> \
  -e QBT_PASS=<password> \
  -e MUSIC_DIR=/music \
  -v /your/data/dir:/data \
  -v /your/music/dir:/music:ro \
  projection-room
```

### 6.3 Building the clients

```bash
cd native
./gradlew :app-tv:assembleDebug      # TV: native/app-tv/build/outputs/apk/debug/
./gradlew :app-phone:assembleDebug   # Phone: native/app-phone/build/outputs/apk/debug/
adb install -r <apk>
```

JDK 17, compileSdk 35. APKs are signed with the fixed key shipped in the repo
(`native/keystore/proom.jks`, password `proom-release`) so that any build can upgrade any other.
The key is public and adds no security; set `PROOM_KEYSTORE` and `PROOM_KEYSTORE_PASS` to sign with your own.

On the TV, allow "install unknown apps" the first time; some TV systems show an unverified-app warning, accept it and continue.

---

## 7. First run

Nothing is bundled: no film sources, no channels, no music API. A "no sources yet" screen on first launch is expected.

> Do not expose port 8899 to the internet: there is no authentication by default and the service is LAN-only. Add real authentication in front if you need remote access.

Configure in this order:

1. **Import sources** — Console → Sources. Paste a TVBox config URL to import in bulk.
2. **Import live TV** — Console → Live sources. Enter candidate URLs; they are re-fetched, merged and health-checked daily.
3. **Add music** (optional) — a local folder or Navidrome / Airsonic / Gonic; online playlists and search need endpoints you provide.

The repository contains no scraping-site addresses, channel lists or music endpoints. Three ways to load your own:

| Method | How | Use case |
|---|---|---|
| Config bundle | Console → Backup → Import a JSON | Migrating from another machine |
| Manually | Add sources one by one, or paste a TVBox config URL | A few entries |
| Seed file | Drop an exported `seed.json` at `data/seed.json` and start | Full setup at once |

`seed.json` uses the same format as a config bundle:

```bash
# Export from the current database (contains real addresses — keep it local)
python3 tools/export-seed.py                 # → data/seed.json

# Another machine: copy it over to the same path and it is imported on startup
DB_PATH=/data/media.db SEED_FILE=/data/seed.json python3 app.py
```

---

## 8. Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8899` | Listen port |
| `DB_PATH` | `./data/media.db` | Database location; point it at a volume in containers |
| `SEED_FILE` | `./data/seed.json` | Data file imported on startup; absent means an empty start |
| `LIVE_SEEDS` | empty | Candidate live sources, comma separated, used by the daily refresh |
| `CMS_SEEDS` | empty | Source "resource catalogs", comma separated, used by auto-discovery |
| `MUSIC_ONLINE` | `0` | Set to `1` to enable online song search |
| `ONLINE_SEARCH_URL` | empty | Online search endpoint; empty disables it, and `MUSIC_ONLINE=1` is still required |
| `ONLINE_PLAY_URL` | empty | Playback URL converter; empty means no online stream URL is returned |
| `ONLINE_COVER_BASE` | empty | Cover image prefix; empty drops covers from search results |
| `ONLINE_REFERER` | empty | Referer required by the endpoints above |
| `MUSICBOX_API` | empty | Online playlist / stream endpoint; empty disables online music |
| `MUSICBOX_REFERER` | empty | Referer required by the playlist endpoint |
| `LYRIC_API` | empty | Lyrics endpoint, shipped to the app in the config bundle; empty shows "no lyrics" |
| `LYRIC_REFERER` | empty | Referer required by the lyrics endpoint |
| `MAGNET_SITE` | empty | Magnet search site; empty disables magnet search |
| `MUSIC_DIR` | empty | Local music folder; can also be set in the UI |
| `QBT_URL` | `http://127.0.0.1:8080` | qBittorrent address |
| `QBT_USER` / `QBT_PASS` | empty | Environment only; no default credentials |
| `NO_AUTH` | `1` | Set to `0` to enable multi-account mode |
| `PROXY_ALLOW_PRIVATE` | empty | The HLS proxy refuses private addresses by default; set `1` if your sources really are on the LAN |

Entries marked "empty disables" are content entry points: leave them empty and no request is made to any third-party service.

Any of the above can also go into a `.env` file at the repo root; `app.py` reads it on startup (existing environment variables win). `.env` is gitignored, which makes it the right place for real endpoints and credentials.

> This is a **LAN** setup: no password, no HTTPS. Never expose 8899 directly to the internet.

---

## 9. Project layout

```
app.py                  Entry point
server/
  api.py                Routes and auth
  db.py                 SQLite schema and migrations
  services/
    auth.py             Accounts (idle when NO_AUTH=1)
    config_io.py        Config bundle export/import (schema version + migrations)
    musicbox.py         Online playlists (recommended, categories, detail)
    proxy.py            Same-origin HLS proxy (with private-network guard)
    catalog.py          Source aggregation: search / detail / browse
static/
  app/                  Build-free frontend: plain ES modules and hand-written CSS
  index.html            Single-page entry
native/                 Compose Multiplatform clients
  app-tv / app-phone    Two app shells (different package ids, can coexist)
  core / data           Data layer: aggregation, parsing, caching
  ui-tv / ui-phone      Per-client UI (different rules, not shared)
  ui-kit / ui-media     Shared components and player
  keystore/             Release key (fixed signature)
tools/                  export-seed.py, publish-apk.sh, cdp.js screenshot helper
tests/                  Pure-function unit tests (no network, no real database)
docs/                   Config bundle format, architecture and audit notes
```

---

## 10. Development and tests

```bash
python3 -m unittest discover -s tests -v     # pure-function unit tests
cd native && ./gradlew :core:jvmTest         # data-layer tests
cd native && ./gradlew :app-tv:assembleDebug # compiling is a check by itself
```

| CI | Trigger | What it does |
|---|---|---|
| `ci.yml` | push / PR | Python compile, unit tests, ESM syntax check |
| `native.yml` | changes under `native/**` | Data-layer tests, builds both debug APKs, uploads artifacts |

Shipping a build:

```bash
cd native && ./gradlew :app-tv:assembleRelease :app-phone:assembleRelease
cd .. && ./tools/publish-apk.sh tv && ./tools/publish-apk.sh phone
```

`publish-apk.sh` writes the APK plus `{versionCode, versionName, size, sha256, notes}` into `data/apps/`;
the app verifies size and sha256, so a corrupted build never reaches the installer.

---

## 11. License and content notice

### 11.1 Code license

**MIT License**. Full text in [`LICENSE`](LICENSE).

```
Copyright (c) 2026 lsjkbz
```

The name "Private Screening / 放映室", the interface design, the copy and the icons are original work
for this project and are licensed under the same terms.

### 11.2 Third-party components

| Component | License | Where |
|---|---|---|
| Inter | SIL Open Font License 1.1 | `static/fonts/` |
| Noto Sans SC | SIL Open Font License 1.1 | `static/fonts/` |
| ArtPlayer 5.4.0 | MIT | `static/artplayer.js` |
| hls.js 1.5.17 | Apache License 2.0 | `static/hls.js` |
| Compose Multiplatform / AndroidX | Apache License 2.0 | Gradle dependencies |
| Glide, OkHttp, kotlinx.serialization | Apache License 2.0 | Gradle dependencies |

Font license texts and provenance are in `static/fonts/README.md`. Every frontend asset is self-hosted.

### 11.3 Content notice

**This project holds, provides and distributes no audio or video content, and points at no content provider.**

- The repository contains **no** scraping-site addresses, CMS endpoint lists, channel lists, music APIs or magnet domains — those live in local files covered by `.gitignore` and are supplied by the user;
- The config bundle in the APK (`assets/config/bundle.json`) is an empty shell with no addresses;
- Playback URLs are returned by the endpoints the user configures;
- Music comes from a Navidrome / Airsonic / Gonic server the user runs, or an endpoint the user provides;
- The repository contains no media files and no playback URL lists.

All content remains the property of its respective rights holders. Which sources you configure, what you
play and where you play it from is your decision and your responsibility.
This project does not recommend, endorse or maintain any third-party source, and makes no judgement
about their legality, availability or content. Third-party names, trademarks and service marks belong to
their owners, are used only to describe compatibility, and imply no affiliation or endorsement.

### 11.4 Disclaimer

**Read this in full before use. Using the project means you have read, understood and accepted all of the following.**

1. **This project provides no content.** It does not store, host, transcode, distribute or sell any film, channel or music.
2. **All content comes from third parties.** Search, detail and playback URLs are returned by third-party APIs; this project only parses, renders and proxies same-origin requests, with no warranty as to legality, accuracy or availability.
3. **No affiliation with any content provider.** No relationship, partnership or license with any platform, broadcaster, rights holder or scraping site.
4. **You are solely responsible.** Verify the laws of your jurisdiction and bear full responsibility for installing, configuring, accessing, playing and downloading anything.
5. **No commercial or public use.** Not for commercial use, paid services, public broadcast, redistribution or public-facing deployment.
6. **Claims of infringement.** This project holds no content and cannot remove anything from third-party sites; address the site that hosts it.
7. **No support, no availability guarantee.** Provided "as is", with no promise that any endpoint stays up or that playback quality is adequate.
8. **If you disagree, stop using it now** and delete the project and all data it produced.
