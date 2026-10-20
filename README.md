# Marquee TV

A Netflix-style Android TV front end for your existing Stremio setup. Sign in with your Stremio account and it loads everything automatically: your installed addons (Duck Streams / AIOStreams included), their catalogs, your library, Continue Watching and watch progress.

## Install on the TV

1. On the TV, install **Downloader** (by AFTVnews) from the Play Store.
2. Allow Downloader to install unknown apps when prompted.
3. Enter: `https://github.com/maximhoule/Netflixx/releases/latest/download/Marquee.apk`
4. Open **Marquee**, sign in with your Stremio email and password.

Every push to `main` builds a new APK; reinstalling from the same link updates in place.

## What it does

- **Home / Series / Movies**: big billboard that follows whatever you're focused on, with rows from every catalog addon in your Stremio order.
- **Continue Watching**: from your Stremio library, plus anything watched in this app. Selecting a tile resumes immediately.
- **Play**: picks the top source from your stream addons and starts it. If a source fails, it moves to the next one automatically.
- **Sources**: the full source list, if you want to choose.
- **Autoplay next episode**, resume position, subtitles from your subtitle addons.
- **Progress sync**: writes your position back to Stremio, so Continue Watching matches your other devices.
- **Built-in player** (ExoPlayer with FFmpeg audio for DTS/TrueHD) or hand-off to VLC / Just Player / MX Player.

## Remote

- Left / right during playback: skip 10 seconds. OK: pause.
- Back from content opens the side menu; Back again exits.

## Limits

- Torrent-only streams (no debrid) are skipped, since this app has no torrent engine. Duck Streams uses debrid links, so it works.
- Addons are managed in Stremio; use **Settings → Reload addons** after changing them.
