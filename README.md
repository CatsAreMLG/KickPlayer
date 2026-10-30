# Kick Player

An Android app for watching Kick live streams and VODs with proper media controls. The official Kick app drops its controls in picture-in-picture, shows nothing on a smartwatch, and ignores earbud buttons while the screen is off. Kick Player registers a standard Android media session, so playback works with the notification, lock screen, Bluetooth earbuds, and Wear OS watches like any other media app.

This is an unofficial, personal project. It is not affiliated with Kick, and it relies on Kick's undocumented web API, which can change without notice.

## Features

**Playback anywhere.** Audio and video keep playing with the screen off or the app in the background. Play, pause, and skip work from the notification, lock screen, earbuds, and a paired watch. Picture-in-picture starts automatically when you leave the app mid-stream, with back 10s, play/pause, and forward 10s buttons in the floating window.

**Live streams.** Type any channel name or paste a kick.com link. The progress bar ends at the live point, so being live puts the scrubber at the far right. While behind, the time readout shows how far behind live you are. Rewinding reaches back up to 3 minutes into what you've already watched, past the 30 seconds Kick's live playlist normally allows. Playing faster than 1x drops back to 1x automatically when you catch up to live, and skipping forward never overshoots live.

**VODs.** Paste a VOD link, or type a channel and tap VODs to browse recent ones with thumbnails. The app remembers where you stopped in each VOD and resumes there, including after closing the app.

**Home screen.** Everything you've watched appears under the player with thumbnails. Channels show a LIVE or OFFLINE badge, checked each time you open the app. VODs show their length and a progress bar. Long-press an entry to remove it.

**Player controls.**
- Gear menu: playback speed (1x to 2x), video quality (Auto or a fixed resolution, remembered across streams), and Go to live.
- CC button: one-tap caption toggle, remembered across streams. Captions sit inside margins and show at most two lines.
- Double-tap the left or right half of the video to skip back or forward 10 seconds.
- Fullscreen: tap the fullscreen button or turn the phone sideways. Turn it upright to exit.
- Pinch to zoom in fullscreen, from the whole picture up to 8x, and drag to pan. Snaps to "zoomed to fill" near that level.

**Pause behavior.** A paused stream stays fully loaded for 10 minutes. After that, Android may close the app, but pressing play on your earbuds or the lock screen reloads what you were watching (VODs at your saved spot, channels at live).

## Requirements

- Android 8.0 or newer to run the app.
- Android Studio to build it, with the Android 15 (API 35) SDK platform installed (Settings > Languages & Frameworks > Android SDK > SDK Platforms).

## Building and installing

1. Open the `KickPlayer` folder in Android Studio and let Gradle sync.
2. On your phone, enable Developer options (tap Build number 7 times under Settings > About phone), then turn on USB debugging or Wireless debugging.
3. Connect the phone and press Run.

The app stays installed after you disconnect. To update it later, reconnect and press Run again; your settings, recents, and VOD positions carry over.

## Sharing the APK

Build a standalone installer with Build > Build App Bundle(s) / APK(s) > Build APK(s). The file appears at `app/build/outputs/apk/debug/app-debug.apk`. Recipients tap the file to install, allow installs from the app that opened it, and tap "Install anyway" if Play Protect warns about an unrecognized app.

The APK is signed with the debug key Android Studio keeps on the building computer (`.android/debug.keystore` in your user folder). Updates only install over an existing copy if they carry the same key, so back that file up.

Google is phasing in developer verification for sideloaded apps, starting in select countries in late 2026 and expanding globally in 2027. Once it reaches a region, unregistered apps need an extra installation flow or ADB. Installing from Android Studio uses ADB.

## Project layout

All source lives in `app/src/main/java/app/kickplayer/`.

| File | Purpose |
| --- | --- |
| `MainActivity.kt` | The screen: search bar, player, now-playing info, home list, gestures, fullscreen, picture-in-picture. |
| `PlaybackService.kt` | Background service that owns the player and media session. Handles buffering, skip rules on live, saving VOD positions, and resuming after the app closes. |
| `KickApi.kt` | Looks up channels and VODs from Kick's web API, with a hidden WebView fallback when Cloudflare blocks plain requests. |
| `Live.kt` | Live-stream math: distance behind live, how far back rewinding can reach, and the remapped progress bar. |
| `Recents.kt` | Recently watched list. |
| `Prefs.kt` | Saved settings (quality, captions, last channel) and per-VOD resume positions. |
| `KickPlayerApp.kt` | Image loading setup for thumbnails. |

Built with Kotlin, AndroidX Media3 (ExoPlayer and MediaSession), OkHttp, and Coil.

## Known limitations

- Kick's API is undocumented. If stream lookup, VOD lists, or thumbnails break, the response format likely changed.
- Subscriber-only VODs don't play, since the app has no Kick login.
- Live rewind is limited to what the app has downloaded since you opened the stream, up to 3 minutes.
- The Pixel Watch may not show a resume option once Android has closed the app; earbuds and the lock screen still work.
- No chat.

## Privacy

The app has no account system, analytics, or ads. Network requests go only to Kick (stream lookups, video, and images). Settings, recents, and VOD positions are stored on the device.
