# 放映室 · Private Screening

**English** · [中文](README.md)

Version **0.15.0** · One codebase for web, Android phone and Android TV

> **An empty player.** The program ships no sources, channels, music endpoints or preset configuration;
> the repository contains no media files and no content addresses. Sources are supplied by the user.
> An empty first launch is expected.

## Contents

[1. Scope](#1-scope) · [2. Features](#2-features) · [3. Clients](#3-clients) · [4. Architecture](#4-architecture) ·
[5. Screenshots](#5-screenshots) · [6. Deployment](#6-deployment) · [7. First run](#7-first-run) ·
[8. Environment variables](#8-environment-variables) · [9. Project layout](#9-project-layout) ·
[10. Development and tests](#10-development-and-tests) · [11. License and notice](#11-license-and-notice)

---

## 1. Scope

| Item | Notes |
|---|---|
| What it is | A unified player frontend for films, live TV and music, with a config-distribution and update backend |
| What it is not | No scraping, storing or distributing of works; no content subscriptions |
| Content | Scraping-site APIs, channel lists and music services supplied by the user |
| Preset config | None; no connection details are built in |
| Accounts | No login, no PIN |
| Runtime | No build step; `python3 app.py`, no pip / Node / bundling |
| Offline | Search, browse and play with the NAS off |
| Nature | Personal project, published as-is |

---

## 2. Features

### 2.1 Films and series

| Capability | Notes |
|---|---|
| Aggregated search | Queries every source concurrently, merges results by title, lists all routes |
| Multi-route playback | Routes labelled by quality (4K / 1080P / 720P / HD), automatic failover |
| Episodic series | Ordered by episode; progress kept when switching routes; auto-continues |
| Library browsing | Films / series / anime / variety / documentary, filtered by year, region or source |
| Source management | Bulk import from TVBox config URLs, priority ordering, per-source test and toggle (web) |
| Downloads | Connects to your own qBittorrent; magnet results show seeder counts, one-click submit (web) |

### 2.2 Live TV

| Capability | Notes |
|---|---|
| Import and grouping | M3U / TXT import, automatic grouping, duplicate channels merged into routes |
| Health checks | One-click check; dead channels are flagged but kept |
| Daily aggregation | Candidate sources fetched, merged, deduplicated and checked daily; only working channels kept |
| Maintenance | Group view, manual refresh, batch checks (web) |

### 2.3 Music

| Capability | Notes |
|---|---|
| Library | Local folder (ID3 / FLAC), Subsonic servers (Navidrome / Airsonic / Gonic), online playlists |
| Discovery | Recommended and category playlists, album wall, artists, online search |
| Playback | Queue, loop modes, scrolling lyrics, remembered mode |
| Equalizer | Web: 10-band EQ with 8 presets; TV presets adapted per device |
| Per client | Phone: vinyl record and line-by-line lyrics; TV: record left, lyrics right, D-pad track switching |

### 2.4 Player

| Capability | Notes |
|---|---|
| Gestures and remote | Tap to pause / play, double tap for fullscreen; TV uses D-pad, OK and BACK |
| Resume | Progress recorded per second; playback resumes from the last position |
| Buffering state | Blurred poster, bouncing dots and live bitrate |
| Picture-in-picture | Keep browsing, searching and switching titles in a small window |
| Subtitles | App: switch embedded tracks, attach local SRT / VTT / ASS / TTML, remembered per title + episode, four size steps. Web: external files only |
| Audio tracks | App: manual selection between multiple tracks |
| Skip intro / outro | Ranges recorded per series; intro shows a skip button, outro offers the next episode with a countdown; stored locally |

### 2.5 Casting

The phone starts playback, the TV plays in place and reports state back; the screen is never mirrored.
Channels are selected by availability:

| Channel | Setup | Capability |
|---|---|---|
| NAS relay | Both clients on the same NAS | Full: speed, volume, progress read-back; works across subnets |
| LAN direct | Automatic discovery on the same LAN | Full; no NAS involved |
| DLNA | TV as a standard DLNA renderer | Play / pause / seek / stop; no progress read-back |

When LAN discovery is blocked, pair by QR code (the TV shows a code) or enter the address manually.

### 2.6 Management

| Capability | Notes |
|---|---|
| Config backup | Export everything or sources only; preview before import, backup before write, rollback |
| Config sharing | Sources, live lists, music and interface preferences packed into one JSON |
| Config distribution | The NAS serves the bundle; the app updates in the background and never re-renders unchanged content |
| Download tasks | Pause / resume / remove, status refreshed automatically |
| Self-check | Backend reachability, source availability, version info |
| In-app updates | Version check on launch, verified before handing off to the system installer |

---

## 3. Clients

| | Desktop browser | Phone app | TV app |
|---|---|---|---|
| Package | — (browser) | `com.projectionroom.app` | `com.projectionroom.tv` |
| Role | Console + player | Portable player | Large-screen player |
| Input | Mouse and keyboard | Touch | Remote |
| Browse / search / play | ✅ | ✅ | ✅ |
| Favourites / history | ✅ | ✅ | ✅ |
| Music | ✅ Full | ✅ Full | ✅ Full (with EQ presets) |
| Casting | Receiver control | ✅ Sender | Receiver |
| Configuration | ✅ All entry points | Subscriptions + import / export | Subscriptions + import / export |
| Downloads / scheduled backup | ✅ | ❌ | ❌ |

The phone app has four tabs (films / live / music / profile). The TV app returns with BACK only, exposes no
focusable back button and places initial focus on the first actionable item.

---

## 4. Architecture

The web app runs on the NAS, the apps run on the devices; they share protocol and configuration, not a runtime.

### 4.1 Layers

```
Desktop browser          Phone app                    TV app
static/app/             ui-phone + app-phone         ui-tv + app-tv
native ESM, no build         │                            │
     │                   ┌────┴────────────────────────────┴────┐
     │                   │ ui-media  player / music / live (shared)│
     │                   │ data      storage / sync / pairing / DLNA│
     │                   │ core      pure Kotlin: protocol / merge│
     │                   └─────────────────────────────────────┘
server/ (Python standard library, no framework, no pip)
```

| Layer | Location | Responsibility |
|---|---|---|
| Protocol | `native/core` | MacCMS parsing, cross-source merge, title normalisation, quality tiers, cast protocol |
| Platform | `native/data` | Storage, config bundle, NAS gate, favourite sync, LAN discovery, DLNA |
| UI base | `native/ui-kit` | Theme, icons, skeletons, backgrounds |
| Shared screens | `native/ui-media` | Player, music library, live library |
| Client screens | `ui-phone` / `ui-tv` | Gestures / 10-foot focus ring |
| Web | `static/app` + `server/` | Same `/api/*` semantics as the apps |

Dependencies flow one way: client screens → shared screens → UI base → platform → protocol.

### 4.2 NAS responsibilities

| Responsibility | Endpoints |
|---|---|
| Sync | `/api/fav` `/api/history` `/api/library` |
| Casting | `/api/cast/*` |
| Updates | `/api/app/latest` `/api/app/download` |
| Config | `/api/config/bundle` |

Everything else is resource loading (home, library, search, detail, streams, playback, covers, music) and
goes from the client straight to the source. Web HLS is relayed through `/api/proxy` to work around CORS.

### 4.3 Local data

| Data | Primary | Sync |
|---|---|---|
| Sources / live / music config | NAS | Rewritten when the version differs |
| Favourites | Device | Written locally, pushed in the background |
| Playback history | Device | Written locally, pushed in the background |
| Home feed | Local aggregation | Snapshot rebuilt after 15 minutes |
| Cast progress | TV | Reported to the phone in real time |

Configuration and cache live on the device; with the NAS offline, home, search and playback still work.

### 4.4 Discovery and pairing

```
Phone first launch ──► UDP broadcast PROOM_DISCOVER to 8897 ──► NAS discovery_loop replies PROOM_OFFER
Phone ⇄ TV ──► same-subnet UDP reply (LAN direct, no NAS)
               └─ blocked: the TV shows a QR code and pairing code, the phone connects directly
```

The pairing is written to local settings; later launches do not require the NAS.

---

## 5. Screenshots

Screenshots show interface structure, configuration and pairing only; they contain no source data or
third-party title information.

Config backup and sharing (preview before import, backup before write):

![Config backup](docs/screenshot-backup.png)

TV pairing by QR code (direct connection when LAN discovery is blocked):

<p>
<img src="docs/screenshot-app-tv-pair.png" width="560" alt="TV pairing by QR code">
</p>

---

## 6. Deployment

### 6.1 Run directly

```bash
git clone https://github.com/saury91/nas-screening.git
cd nas-screening
python3 app.py
```

Open `http://127.0.0.1:8899`. The database is created on first run; home and the five category pre-warms
take about half a minute in the background.

### 6.2 Docker

```bash
docker build -t projection-room .

docker run -d --name projection-room --restart unless-stopped --network host \
  -e DB_PATH=/data/media.db \
  -v /your/data:/data \
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
  -v /your/data:/data \
  -v /your/music:/music:ro \
  projection-room
```

### 6.3 Building the clients

```bash
cd native
./gradlew :app-tv:assembleDebug      # TV: native/app-tv/build/outputs/apk/debug/
./gradlew :app-phone:assembleDebug   # Phone: native/app-phone/build/outputs/apk/debug/
adb install -r <apk>
```

JDK 17, compileSdk 35. APKs are signed with the fixed key in `native/keystore/proom.jks` (password
`proom-release`) so any build can upgrade any other; the key is public and provides no security.
Override with `PROOM_KEYSTORE` and `PROOM_KEYSTORE_PASS` for independent signing.

The TV needs "install unknown apps" permission the first time.

---

## 7. First run

> Port 8899 has no authentication by default and is LAN-only. Do not expose it to the internet;
> put real authentication in front if you need remote access.

The app ships with no sources, so an empty library on first launch is expected. Configure in this order:

1. **Import sources** — Console → Sources. Paste a TVBox config URL to import in bulk.
2. **Import live TV** — Console → Live sources. Enter candidate URLs; they are fetched, merged and checked daily.
3. **Add music** (optional) — a local folder or Navidrome / Airsonic / Gonic; online playlists and search need endpoints you provide.

Three ways to load your own data:

| Method | How | Use case |
|---|---|---|
| Config bundle | Console → Backup → Import a JSON | Migrating from another machine |
| Manually | Add sources one by one, or paste a TVBox config URL | A few entries |
| Seed file | Drop an exported `seed.json` at `data/seed.json` and start | Full setup at once |

```bash
# Export from the current database
python3 tools/export-seed.py                 # → data/seed.json

# Import on start
DB_PATH=/data/media.db SEED_FILE=/data/seed.json python3 app.py
```

---

## 8. Environment variables

| Variable | Default | Notes |
|---|---|---|
| `PORT` | `8899` | Listen port |
| `DB_PATH` | `./data/media.db` | Database location; point at a mounted volume in Docker |
| `SEED_FILE` | `./data/seed.json` | Imported on start when present |
| `LIVE_SEEDS` | empty | Candidate live sources, comma separated, for daily aggregation |
| `CMS_SEEDS` | empty | Source catalogue URLs, comma separated, for auto-discovery |
| `MUSIC_ONLINE` | `0` | Set to `1` to enable online music search |
| `ONLINE_SEARCH_URL` | empty | Online search endpoint; empty disables it |
| `ONLINE_PLAY_URL` | empty | Playback address resolver |
| `ONLINE_COVER_BASE` | empty | Cover URL prefix for online results |
| `ONLINE_REFERER` | empty | Referer required by the endpoints above |
| `MUSICBOX_API` | empty | Online playlist / playback endpoint; empty disables online music |
| `MUSICBOX_REFERER` | empty | Referer for the playlist endpoint |
| `LYRIC_API` | empty | Lyrics endpoint, shipped to the apps in the config bundle |
| `LYRIC_REFERER` | empty | Referer for the lyrics endpoint |
| `MAGNET_SITE` | empty | Magnet search site; empty disables magnet search |
| `MUSIC_DIR` | empty | Local music folder; can also be set in the UI |
| `QBT_URL` | `http://127.0.0.1:8080` | qBittorrent address |
| `QBT_USER` / `QBT_PASS` | empty | Environment only; no default credentials |
| `NO_AUTH` | `1` | Set to `0` for multi-account mode |
| `PROXY_ALLOW_PRIVATE` | empty | The HLS proxy refuses private addresses; set to `1` to allow them |

Any of these can also go into a `.env` file at the repo root; `app.py` reads it on startup (existing
environment variables win).

---

## 9. Project layout

```
nas-screening/
├── app.py                      Backend entry point (Python standard library, no framework)
├── server/                     Backend modules
│   ├── api/                    Routes: proxy / source / fav / history / cast …
│   ├── sources.py              Sources: config parsing / search / detail / routes (cached)
│   ├── live_sources.py         Live TV: import / health checks / daily aggregation
│   ├── music*.py               Music: local / Subsonic / online
│   ├── db.py                   Storage layer
│   └── app_upgrade.py          APK version check and delivery
├── static/
│   ├── app/                    Web console (native ESM, no build)
│   ├── styles/  theme.js       Styles and theme
│   └── vendors/                Vendored third-party libraries
├── native/                     Android (Kotlin Multiplatform)
│   ├── core/                   Protocol / merge / normalisation (pure Kotlin)
│   ├── data/                   Storage / sync / pairing / DLNA
│   ├── ui-kit/  ui-media/      UI base and shared screens
│   ├── app-phone/  app-tv/     Phone and TV apps
├── docs/                       Screenshots
├── tools/                      Release and import helpers
└── tests/                      Python unit tests
```

---

## 10. Development and tests

```bash
python3 -m unittest discover -s tests            # Python
cd native && ./gradlew :core:testDebugUnitTest   # Kotlin (protocol layer has the widest coverage)

./scripts/publish-apk.sh                         # Build and write APK version info
```

`publish-apk.sh` writes the APK plus `{versionCode, versionName, size, sha256, notes}` into `data/apps/`;
the app verifies size and sha256 before handing off to the installer.

Pushing a `v*` tag runs both test suites and builds all three artefacts in GitHub Actions.

---

## 11. License and notice

Released under the [MIT License](LICENSE), provided as is, without warranty of any kind.

This project does not provide, store or distribute any film or music content. Copyright remains with the
respective rights holders; which sources you connect, what you play and where you play it from is your
decision and your sole responsibility. The project does not recommend, endorse or maintain any third-party
source and makes no judgement about its legality, availability or content.

The author accepts no liability for any direct or indirect loss arising from use of this project.
Downloading, deploying or otherwise using it constitutes acceptance of this notice. Third-party names and
trademarks belong to their owners and are used only to describe compatibility.
