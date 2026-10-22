# Private Screening

[中文](README.md) · **English**

Films, live TV and music from your NAS in one interface. The same frontend runs in a browser, on a phone and on a TV.

- Version **0.13.0** (Android TV / Phone / Web, one codebase)
- **No bundled content sources**: no film sources, channel lists or music APIs ship with it — you get an empty shell and bring your own config
- No build step for the frontend: `python3 app.py` and you're done. No pip, no Node, no bundling
- The apps keep searching, browsing and playing even when the NAS is off

---

## Contents

- [Features](#features)
  - [Films](#films)
  - [Live TV](#live-tv)
  - [Music](#music)
  - [Player](#player)
  - [Casting](#casting)
  - [Admin and backup](#admin-and-backup)
  - [Independence](#independence)
- [The three clients](#the-three-clients)
- [Screenshots](#screenshots)
- [How it runs](#how-it-runs)
- [Getting started](#getting-started)
  - [First launch: the empty shell is expected](#first-launch-the-empty-shell-is-expected)
  - [Bring your own sources](#bring-your-own-sources)
- [Environment variables](#environment-variables)
- [Project layout](#project-layout)
- [Development and tests](#development-and-tests)
- [Copyright and license](#copyright-and-license)

---

## Features

### Films

| Capability | What it does |
|---|---|
| Aggregated search | Asks every source at once, merges the same title, lists all of its streams |
| Multi-stream playback | Each stream is tagged with its quality (4K / 1080P / 720P / HD); switch when one stalls |
| Series | Episodes in order, progress preserved across stream changes, auto-advance |
| Library browsing | Movies / series / anime / variety / documentary, filtered by year, region or source |
| Source management | Import a TVBox config URL in one tap, reorder by priority, test individually, toggle (Web) |
| Downloads | Your own qBittorrent; magnet results show seeders, one tap to submit (Web) |

### Live TV

| Capability | What it does |
|---|---|
| Import and grouping | M3U / TXT import, auto-grouped, same-name channels deduped |
| Health check | One tap to probe; dead channels are dimmed and kept, never removed automatically |
| Daily refresh | Fetches candidate sources daily, merges and health-checks them, keeps what plays |
| Maintenance | Grouped view, manual refresh, batch health check (Web) |

### Music

| Capability | What it does |
|---|---|
| Library | Local FLAC/ID3, Subsonic-compatible servers (Navidrome / Airsonic / Gonic), online playlists |
| Discovery | Recommended and category playlists (theme · language · style · scene · mood), albums, artists, online search |
| Playback | Queue, repeat modes, scrolling lyrics, waveform progress, remembered playback mode |
| DSP | Web gets a 10-band EQ with 8 presets; the TV's presets adapt to the device's own frequency bands |

All three clients play music: the phone shows a spinning record with line-by-line lyrics and a mini player under every tab; the TV shows the record left and lyrics right, with the D-pad changing tracks and seeking.

### Player

| Capability | Notes |
|---|---|
| Gestures and remote | Tap to pause/play, double-tap for fullscreen; the TV uses D-pad focus, OK, BACK |
| Resume | Progress saved every few seconds; picks up where you stopped |
| Buffering state | Blurred poster, three bouncing dots, live bitrate — never a spinner |
| Picture-in-picture | Browse, search and switch titles in a small window; closing it ends playback |

### Casting

The phone hands a title to the TV and turns into the remote. The TV is a receiver: it plays in place and reports real progress rather than mirroring the phone's screen.

Three transports, chosen automatically; keys the active transport doesn't support are greyed out:

| Transport | How it connects | What it can do |
|---|---|---|
| NAS relay | Both ends connect to the same NAS | Everything: speed, volume, progress read-back; works across subnets |
| LAN peer-to-peer | Auto-discovered on the same LAN | Everything, without the NAS — works with the NAS off |
| DLNA | The TV acts as a standard DLNA renderer | Play / pause / seek / stop only; no speed, volume or progress read-back |

If LAN discovery is blocked by the router, pair via the QR code and PIN shown on the TV, or enter the address manually.

### Admin and backup

| Capability | Notes |
|---|---|
| Config backup | Export everything or sources only; import shows a preview, backs up first, can roll back |
| Config sharing | Sources, live feeds, music and UI preferences in one JSON — exportable and importable |
| Config distribution | The NAS only ships a config bundle; apps refresh it in the background without redrawing |
| Download tasks | Pause / resume / delete, auto-refreshing status |
| About and self-check | Backend reachability, source availability, version info |
| Self-update | On launch the app checks `/api/app/latest` and hands a new build to the system installer |

### Independence

| Capability | Notes |
|---|---|
| Standalone apps | Search, browse and play with the NAS off, using the last synced config |
| No accounts | Open and go — no login, no PIN (`NO_AUTH=1` by default) |
| Local cache | Page data and title index live on device; render first, refresh after |
| Zero third-party deps | No build step, self-hosted fonts, no CDN references |

---

## The three clients

| | Desktop web | Phone app | TV app |
|---|---|---|---|
| Package | — (open in a browser) | `com.projectionroom.app` | `com.projectionroom.tv` |
| Role | **Admin console + player** | **Pocket player** | **Big-screen player** |
| Input | Keyboard and mouse | Touch | D-pad remote |
| Browse / search / play | ✅ | ✅ | ✅ |
| Favorites and history | ✅ | ✅ | ✅ |
| Music | ✅ full | ✅ full | ✅ full (with EQ presets) |
| Casting | Receiver controls | ✅ sender | Receiver |
| Sources / live feeds / downloads / backup | ✅ everywhere | ❌ not offered | ❌ not offered |

### Desktop web (admin console)

Open `http://<nas>:8899` — always the latest build, nothing to install.

- **Everything**: films, live TV, music, search, playback, casting
- **Everything else too**: source import and ordering, live feed maintenance, download tasks, config backup
- Adaptive content width; layouts cover 375 to 3840

![Home](docs/screenshot-home.png)

### Phone app

The pocket client. It offers no configuration management: anything that changes server state happens in the desktop console.

- Four bottom tabs: Films / Live / Music / Mine
- "Mine" merges favorites and history into one screen
- Same playback gestures: tap to pause, double-tap for fullscreen
- When casting, the phone becomes a remote: seek, volume, speed, episode and stream switching all stay in hand

<p>
<img src="docs/screenshot-app-phone.png" width="240" alt="Phone home">
<img src="docs/screenshot-app-phone-detail.png" width="240" alt="Phone detail">
<img src="docs/screenshot-app-phone-mine.png" width="240" alt="Phone mine">
</p>

### TV app

Remote only, 10-foot UI:

- BACK is the only way back; pages carry no focusable back button
- Initial focus lands on the first actionable item (first card or primary button)
- No lock screen, picture-in-picture or casting entry
- Ambient backdrops look the same on Android 8.0 and newer

<p>
<img src="docs/screenshot-app-tv.png" width="380" alt="TV home">
<img src="docs/screenshot-app-tv-detail.png" width="380" alt="TV detail">
</p>

---

## Screenshots

### Film screenshots

One search asks every source at once; the same title is merged and all its streams listed.

![Search](docs/screenshot-search.png)

The detail screen shows synopsis, director, cast, rating and the quality of every stream.

![Detail](docs/screenshot-detail.png)

The phone runs the same logic reflowed for portrait; a trending term is one tap away:

<p>
<img src="docs/screenshot-app-phone-search.png" width="240" alt="Phone search">
</p>

### Live TV screenshots

Imported feeds are grouped automatically, health-checked in one tap, and refreshed daily.

![Live](docs/screenshot-live.png)

The phone and the TV share one channel table: three wide columns on the phone (409 channels · 40 groups), focus-friendly tiles on the TV.

<p>
<img src="docs/screenshot-app-phone-live.png" width="240" alt="Phone live TV">
<img src="docs/screenshot-app-tv-live.png" width="480" alt="TV live TV">
</p>

### Music screenshots

All three share one library with the same "recommended + browsable" structure, and a player fitted to each screen.

**PC**: a recommended playlist opens as its own screen with a "play all" button; the bottom bar carries waveform progress, repeat mode and the queue, and expands into the full player with a 10-band EQ and 8 presets.

![Music](docs/screenshot-music.png)

![Playlist](docs/screenshot-playlist.png)

![Music player](docs/screenshot-music-player.png)

**Phone**: a spinning record with line-by-line lyrics, and the mini player stays pinned under every tab — switching to live TV does not stop the music.

<p>
<img src="docs/screenshot-app-phone-music.png" width="240" alt="Phone music">
<img src="docs/screenshot-app-phone-playlist.png" width="240" alt="Phone playlist">
<img src="docs/screenshot-app-phone-music-player.png" width="240" alt="Phone music player">
</p>

**TV**: the 10-foot cut — record on the left, lyrics on the right, D-pad up/down to change track and left/right to seek; EQ presets adapt to the device's own frequency bands.

<p>
<img src="docs/screenshot-app-tv-music.png" width="380" alt="TV music">
<img src="docs/screenshot-app-tv-music-player.png" width="380" alt="TV music player">
</p>

![EQ presets](docs/screenshot-app-tv-eq.png)

### Player screenshot

Tap to pause, double-tap for fullscreen; buffering shows a blurred poster, dots and live bitrate.

![Player](docs/screenshot-player.png)

### Casting screenshots

![Cast targets](docs/screenshot-app-phone-cast.png)

If LAN discovery is blocked, the TV shows a pairing QR code and PIN; the phone scans once:

![TV pairing](docs/screenshot-app-tv-pair.png)

The phone as a remote: seek, ±10s, speed, volume, episodes and stream switching all in hand.

<p>
<img src="docs/screenshot-app-phone-remote.png" width="300" alt="Casting remote">
</p>

### Backup screen

Sources, live feeds, music and UI preferences pack into one JSON; imports show a preview and can be rolled back.

![Config backup](docs/screenshot-backup.png)

---

## How it runs

The NAS distributes configuration and updates; searching and playback go straight from the client to the source.

- **NAS off**: the app still searches, shows details and plays, with the config last synced
- **NAS on**: config refreshes in the background, and the UI is not redrawn when nothing changed
- **Desktop**: `http://<nas>:8899` is always the latest build, nothing to install

See [`docs/standalone-arch.md`](docs/standalone-arch.md) for the full design.

---

## Getting started

### Run it directly

```bash
git clone https://github.com/saury91/nas-screening.git
cd nas-screening
python3 app.py
```

Open `http://127.0.0.1:8899`. The database is created on first run; home recommendations take a dozen seconds to warm up.

### Docker

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

### First launch: the empty shell is expected

Nothing is bundled: no film sources, no channels, no music API. A "no sources yet" screen on first launch is expected.

Configure in this order:

1. **Do not expose port 8899 to the internet.** There is no authentication by default; it is a LAN service. Put a real auth layer in front if you need remote access.
2. **Import sources** — Console → Sources. Paste a TVBox config URL to import in bulk (see "Bring your own sources" below).
3. **Import live TV** — Console → Live sources. Enter candidate URLs; they are re-fetched, merged and health-checked daily.
4. **Add music** (optional) — a local folder or Navidrome / Airsonic / Gonic; online playlists and online search need an endpoint you provide.

### Bring your own sources

The repository contains no scraping-site addresses, no channel lists and no music endpoints. Three ways to load your own:

| Method | How | When |
|---|---|---|
| Config bundle | Console → Backup → Import a JSON | Moving from another machine |
| Manually | Add sources one by one, or paste a TVBox config URL | Just a few |
| seed file | Drop an exported `seed.json` at `data/seed.json` and start | Whole setup at once |

`seed.json` uses the same format as a config bundle:

```bash
# Export from the current database (contains real addresses — keep it local)
python3 tools/export-seed.py                 # → data/seed.json

# Another machine: copy it over, same path, and it is imported on startup
DB_PATH=/data/media.db SEED_FILE=/data/seed.json python3 app.py
```

### Installing on a TV or phone

```bash
cd native
./gradlew :app-tv:assembleDebug      # TV: native/app-tv/build/outputs/apk/debug/
./gradlew :app-phone:assembleDebug   # Phone: native/app-phone/build/outputs/apk/debug/
adb install -r <apk>
```

JDK 17, compileSdk 35.

**Signing and upgrades.** APKs are signed with a fixed key (`native/keystore/proom.jks`, password `proom-release`). Overwriting an installed app requires the same signature, so the key ships with the repository and any build upgrades any other. The key is public and adds no security; set `PROOM_KEYSTORE` and `PROOM_KEYSTORE_PASS` to sign with your own.

On the TV, allow "install unknown apps" the first time. Some TV ROMs show an unverified-app warning; accept it and continue.

---

## Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8899` | Listen port |
| `DB_PATH` | `./data/media.db` | Database location; point it at a volume in containers |
| `SEED_FILE` | `./data/seed.json` | Data file imported on startup; absent means an empty start |
| `LIVE_SEEDS` | empty | Candidate live sources, comma separated, used by the daily refresh |
| `CMS_SEEDS` | empty | Source "resource catalogs", comma separated, used by auto-discovery |
| `MUSIC_ONLINE` | `0` | Set to `1` to enable online song search (third-party music API) |
| `MUSICBOX_API` | empty | Online playlist/stream endpoint; empty disables online music |
| `MUSICBOX_REFERER` | empty | Referer required by the playlist endpoint |
| `MAGNET_SITE` | empty | Magnet search site; empty disables magnet search |
| `MUSIC_DIR` | empty | Local music folder; can also be set in the UI |
| `QBT_URL` | `http://127.0.0.1:8080` | qBittorrent address |
| `QBT_USER` / `QBT_PASS` | empty | Environment only; no default credentials |
| `NO_AUTH` | `1` | Set to `0` to enable multi-account mode (per-person history and favorites) |

Entries marked "empty disables" are content entry points: leave them empty and no request is made to any third-party service.

> This is a **LAN** setup: no password, no HTTPS. Never expose 8899 directly to the internet; if you need remote access, put it behind a reverse proxy with real authentication.

---

## Project layout

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
  keystore/             Release key (fixed signature, see above)
tools/                  account.py recovery script, publish-apk.sh, cdp.js screenshot helper
tests/                  Pure-function unit tests (no network, no real database)
docs/                   Screenshots, config bundle format, audit notes
```

---

## Development and tests

```bash
python3 -m unittest discover -s tests -v     # pure-function unit tests
cd native && ./gradlew :core:jvmTest         # data-layer tests
cd native && ./gradlew :app-tv:assembleDebug # compiling is a check by itself
```

Two CI workflows:

| File | Trigger | What it does |
|---|---|---|
| `ci.yml` | push / PR | Python compile, unit tests, ESM syntax check |
| `native.yml` | changes under `native/**` | Data-layer tests, builds both debug APKs, uploads artifacts |

Shipping a build is one command:

```bash
cd native && ./gradlew :app-tv:assembleRelease :app-phone:assembleRelease
cd .. && ./tools/publish-apk.sh tv && ./tools/publish-apk.sh phone
```

`publish-apk.sh` writes the APK plus `{versionCode, versionName, size, sha256, notes}` into
`data/apps/`; the app verifies size and sha256 before handing anything to the installer, so a
bad build never gets installed.

---

## Copyright and license

### Code license

Released under the **MIT License**. Full text in [`LICENSE`](LICENSE).

```
Copyright (c) 2026 lsjkbz
```

In short: use it, modify it, redistribute it, including commercially — but keep the copyright and license notice, and the author is not liable for what you do with it.

### Name and interface

The name "Private Screening / 放映室", the interface design, the copy and the icons are original work for this project and are licensed under the same MIT terms, together with the logo and other graphic assets.

### Third-party components

| Component | License | Where |
|---|---|---|
| Inter | SIL Open Font License 1.1 | `static/fonts/` |
| Noto Sans SC | SIL Open Font License 1.1 | `static/fonts/` |
| Compose Multiplatform / AndroidX | Apache License 2.0 | Gradle dependencies |
| Glide, OkHttp, kotlinx.serialization | Apache License 2.0 | Gradle dependencies |

Font license texts and provenance are in `static/fonts/README.md`. Every frontend asset is self-hosted; nothing is loaded from a CDN.

### Content copyright

**This project holds, provides and distributes no audio or video content, and points at no content provider.**

- The repository contains **no** scraping-site addresses, CMS endpoint lists, channel lists,
  music APIs or magnet domains — those live in local files covered by `.gitignore` and are
  supplied by the user;
- The config bundle bundled in the APK (`assets/config/bundle.json`) is an empty shell with no addresses;
- Film, series and channel **playback URLs** are returned by endpoints you configured yourself;
- Music comes from a Navidrome / Airsonic / Gonic server you run, or an endpoint you provide;
- The repository contains no media files and no playback URL lists.

All content remains the property of its respective rights holders. What you play and where you play it from is your decision and your responsibility.

Third-party names, trademarks and service marks mentioned in this project belong to their owners and are used only to describe compatibility — they imply no affiliation or endorsement.

### Disclaimer

**Read this in full before use. Using the project means you have read, understood and accepted all of the following.**

1. **This project provides no content.** It does not store, host, transcode, distribute or sell any film, channel or music; the repository contains no media files and no playback URL lists.

2. **All content comes from third parties.** Search, detail and playback URLs are returned by third-party public APIs; this project only parses, renders and proxies same-origin requests. The content, legality, accuracy and availability of those APIs are beyond its control and carry no warranty.

3. **No affiliation with any content provider.** This project has no relationship, partnership or license with any streaming platform, broadcaster, rights holder or scraping site.

4. **You are solely responsible.** You must verify the laws of your jurisdiction and bear full responsibility for installing, configuring, accessing, playing and downloading anything, and for the consequences. The authors and contributors accept no liability for legal disputes, copyright claims, penalties, data loss or hardware damage arising from use.

5. **No commercial or public use.** Do not use this project commercially, as a paid service, for public broadcast or redistribution, or deployed as a public-facing site.

6. **Claims of infringement.** This project holds no content and cannot remove anything from third-party sites. If you believe content is infringing, address the site that hosts it; this project cannot act on your behalf.

7. **No support, no availability guarantee.** Provided "as is", with no promise that any endpoint stays up, that playback quality is adequate, or that it works with your hardware.

8. **If you disagree, stop using it now** and delete the project and all data it produced.
