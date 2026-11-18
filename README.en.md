# Private Screening

[中文](README.md) · **English**

Version **0.15.0** · One codebase for Web / Android phone / Android TV

> **An empty-shell player.** No sources, no channels, no music endpoints, no bundled configuration.
> The repository contains no media files and no content addresses. A blank first launch is by design,
> not a failure. Every content source is found and configured by the user.

---

## Contents

- [1. Scope](#1-scope)
- [2. Feature list](#2-feature-list)
- [3. Client matrix](#3-client-matrix)
- [4. Architecture](#4-architecture)
  - [4.1 Layers and dependency direction](#41-layers-and-dependency-direction)
  - [4.2 One boundary: the NAS does four things](#42-one-boundary-the-nas-does-four-things)
  - [4.3 Standalone operation: the NAS being off is normal](#43-standalone-operation-the-nas-being-off-is-normal)
  - [4.4 Sync: who owns what](#44-sync-who-owns-what)
  - [4.5 How the clients find each other](#45-how-the-clients-find-each-other)
  - [4.6 Why this split](#46-why-this-split)
- [5. Screenshots](#5-screenshots)
- [6. Deployment](#6-deployment)
- [7. First run](#7-first-run)
- [8. Environment variables](#8-environment-variables)
- [9. Project layout](#9-project-layout)
- [10. Development and tests](#10-development-and-tests)
- [11. License and content notice](#11-license-and-content-notice)

---

## 1. Scope

| Item | Description |
|---|---|
| What it is | A unified player frontend for films, live TV and music, plus a thin backend that only distributes config and updates |
| What it is not | A content site: it does not scrape, store or distribute any work, and ships no subscriptions |
| Content sources | Scraping-site APIs, channel lists and music services supplied by the user |
| Bundled config | None. The config bundle is empty; no connection details are built in |
| Nature | A self-use project published as-is, non-commercial, no operations, no support commitment |
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
| Resume | Progress saved every few seconds; picks up where you stopped |
| Buffering state | Blurred poster, three bouncing dots, live bitrate — never a spinner |
| Picture-in-picture | Browse, search and switch titles in a small window; closing it ends playback |
| Subtitles | App only: toggle and switch embedded tracks, attach local SRT / VTT / ASS / TTML; remembered per title + episode; four size steps |
| Audio tracks | App only: pick between multiple audio tracks (dual-language commentary, dub vs. original); defaults to automatic |

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
| Standalone apps | Search, browse and play with the NAS off, using the last synced config |
| No accounts | Open and go — no login, no PIN (`NO_AUTH=1` by default) |
| Local cache | Page data and title index live on device; render first, refresh after |
| Zero third-party deps | No build step, self-hosted fonts, no CDN references |

### 2.8 Interface boundary

The NAS serves four interface groups only: sync (favorites / history / library), cast relay,
update (version check and APK download) and config (bundle plus the channel table it carries).
Everything else is resource loading and goes from the client straight to the source — never through the NAS.
The one exception is the HLS playback proxy (`/api/proxy`): to get around CORS and Referer limits, playlists
and segments are relayed by the NAS, which refuses private addresses by default (`PROXY_ALLOW_PRIVATE=0`).

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

The three clients are not three skins over one runtime: the web app runs on the NAS, the apps run on
the devices, and what they share is **protocol and configuration**, not a process.

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

| Layer | Location | Responsibility | Hard rule |
|---|---|---|---|
| Protocol | `native/core` | MacCMS parsing, cross-site merge, title normalisation, quality buckets, cast payloads | Pure Kotlin, no Android deps, thickest test coverage |
| Platform | `native/data` | Storage, config bundle, NAS gate, favourite sync, discovery, DLNA | Depends on `core` only |
| Visual kit | `native/ui-kit` | Theme, icons, skeletons | No `if (isTv)` allowed |
| Shared UI | `native/ui-media` | Player, music library, live library | Shared, differences injected |
| Per-client UI | `ui-phone` / `ui-tv` | Gestures vs 10-foot focus ring | No cross references |
| Web | `static/app` + `server/` | Same `/api/*` semantics as the apps | Zero build, edit and refresh |

### 4.2 One boundary: the NAS does four things

| Through the NAS | Endpoints | Why it must |
|---|---|---|
| Sync | `/api/fav` `/api/history` `/api/library` | Every device sees the same favourites and progress |
| Cast | `/api/cast/*` | Relay when phone and TV are on different subnets |
| Update | `/api/app/latest` `/api/app/download` | Version check and APK delivery |
| Config | `/api/config/bundle` | Change a source once, all three clients follow |

Everything else is **resource loading** — home, library, search, detail, playback, covers, music —
and goes from the client straight to the source. Not dogma: aggregating on the NAS makes the NAS the
bottleneck, and worse, one NAS shutdown takes every client down with it.

The single exception is the web HLS proxy `/api/proxy`: browsers cannot get past CORS and Referer
rules, so a same-origin relay is unavoidable (private addresses refused by default).

### 4.3 Standalone operation: the NAS being off is normal

All three pillars must hold, otherwise the client is just a shell that needs a NAS:

| Pillar | How | Result |
|---|---|---|
| Config on device | Atomic write (`.bak` → `.tmp` → rename), fall back to `.bak` | Works forever after one sync |
| Cache never expires | No TTL; freshness only decides whether to refresh **in the background** | Home is never blank because the NAS is off |
| Local primary store | Favourites and progress written locally first, pushed when the NAS is reachable | A month offline loses nothing |

The home feed is assembled locally too: first page of each of five categories → merged and deduped →
sorted by year. The snapshot keeps display fields only (`lines` dropped, 1.8 MB → 53 KB),
1.4 s cold start; the NAS is asked only when nothing can be assembled.

### 4.4 Sync: who owns what

| Data | Primary | How it syncs | The trap |
|---|---|---|---|
| Sources / live / music config | NAS | Compare version, rewrite only on change | All three paths (seed / sync now / restore built-in) go through one `ConfigStore.save` |
| Favourites | **Device** | Write local (`pendingFav=true`) → push → clear flag | The NAS side is a **toggle**: pull the current state first and skip when already equal, otherwise "unfavourite" flips into "favourite again" |
| Playback history | **Device** | Same, keyed by title + episode | Progress written per second, last write wins |
| Home feed | Device | Snapshot rebuilt in background after 15 min | Ask the NAS only when local assembly fails |
| Cast progress | TV | TV reports back to the phone | Only the NAS relay and LAN direct channels report; DLNA does not |

Two hard rules:

1. **Sync endpoints bypass the disk cache** — judging "already equal" from a stale snapshot breaks the toggle semantics above;
2. **Failures must trip a breaker** — `NasGate` stops trying for 60 s after one failure; config pull is capped at 8 s, favourite sync at 6 s, both background only and never blocking the UI.

### 4.5 How the clients find each other

```
Phone first run ──► UDP broadcast PROOM_DISCOVER to 8897 ──► NAS discovery_loop replies PROOM_OFFER
                                                                └─ address filled in automatically
Phone ⇄ TV ──► UDP on the same LAN (LAN direct, never through the NAS)
               └─ if the router blocks it: the TV shows a QR code plus a pairing code
```

The pairing is written to local settings and **configured once** — that NAS may never be powered on again.

### 4.6 Why this split

| Choice | Usual approach | Why not |
|---|---|---|
| Two separate apps | One app with `if (isTv)` | Focus-ring and gesture constraints fight each other; one change breaks the other side |
| KMP with native UI | WebView shell | Sources will not send CORS headers to `127.0.0.1`; a shell either proxies everything (NAS bottleneck) or trips everywhere. Native also buys hardware decoding and background playback |
| Zero-build frontend | Vite / React | No Node toolchain on a NAS; edit-and-refresh beats a build pipeline here |
| Four NAS responsibilities | Proxy all traffic | Server-side aggregation becomes the bottleneck, and a shutdown kills everything |

---

## 5. Screenshots

These show interface structure and the configuration / pairing flow only. They contain no source data
and no third-party title information.

Config backup and sharing (preview before import, backup written first):

![Config backup](docs/screenshot-backup.png)

TV pairing by QR code (direct connection when LAN discovery is blocked):

<p>
<img src="docs/screenshot-app-tv-pair.png" width="560" alt="TV pairing">
</p>

---

## 6. Deployment

### 5.1 Run directly

```bash
git clone https://github.com/saury91/nas-screening.git
cd nas-screening
python3 app.py
```

Open `http://127.0.0.1:8899`. The database is created on first run; home recommendations and the five category pre-warms take about half a minute.

### 5.2 Docker

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

### 5.3 Building the clients

```bash
cd native
./gradlew :app-tv:assembleDebug      # TV: native/app-tv/build/outputs/apk/debug/
./gradlew :app-phone:assembleDebug   # Phone: native/app-phone/build/outputs/apk/debug/
adb install -r <apk>
```

JDK 17, compileSdk 35. APKs are signed with the fixed key shipped in the repo
(`native/keystore/proom.jks`, password `proom-release`) so that any build can upgrade any other.
The key is public and adds no security; set `PROOM_KEYSTORE` and `PROOM_KEYSTORE_PASS` to sign with your own.

On the TV, allow "install unknown apps" the first time. Some TV ROMs show an unverified-app warning; accept it and continue.

---

## 7. First run

Nothing is bundled: no film sources, no channels, no music API. A "no sources yet" screen on first launch is expected.

Configure in this order:

1. **Do not expose port 8899 to the internet.** There is no authentication by default; it is a LAN service. Put a real auth layer in front if you need remote access.
2. **Import sources** — Console → Sources. Paste a TVBox config URL to import in bulk.
3. **Import live TV** — Console → Live sources. Enter candidate URLs; they are re-fetched, merged and health-checked daily.
4. **Add music** (optional) — a local folder or Navidrome / Airsonic / Gonic; online playlists and search need endpoints you provide.

The repository contains no scraping-site addresses, no channel lists and no music endpoints. Three ways to load your own:

| Method | How | When |
|---|---|---|
| Config bundle | Console → Backup → Import a JSON | Moving from another machine |
| Manually | Add sources one by one, or paste a TVBox config URL | Just a few |
| Seed file | Drop an exported `seed.json` at `data/seed.json` and start | Whole setup at once |

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

Any of the above can also go into a `.env` file at the repo root; `app.py` reads it on startup (existing environment variables win). `.env` is gitignored, which makes it the right place for your real endpoints and credentials.

> This is a **LAN** setup: no password, no HTTPS. Never expose 8899 directly to the internet; use a reverse proxy with real authentication if you need remote access.

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
the app verifies size and sha256 so a bad build never reaches the installer.

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

All content remains the property of its respective rights holders. What you configure, play and where you
play it from is your decision and your responsibility.

This project does not recommend, endorse or maintain any third-party source, and makes no judgement or
warranty about their legality, availability or content. Where you obtain endpoints, and whether they are
lawful, is unrelated to this project.

Third-party names, trademarks and service marks mentioned here belong to their owners and are used only
to describe compatibility — they imply no affiliation or endorsement.

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
