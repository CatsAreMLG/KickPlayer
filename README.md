# Kick Player

An Android app for watching Kick live streams and VODs with proper media controls. The official Kick app drops its controls in picture-in-picture, shows nothing on a smartwatch, and ignores earbud buttons while the screen is off. Kick Player registers a standard Android media session, so playback works with the notification, lock screen, Bluetooth earbuds, and Wear OS watches like any other media app.

## Download

**[Get the latest APK from Releases](../../releases/latest)**

Download the `.apk` file under Assets and open it on your Android phone (Android 8.0 or newer). Allow installs from your browser or file app when asked, and tap "Install anyway" if Play Protect warns about an unrecognized app. New versions install over the old one and keep your settings, recents, and VOD positions.

## About

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

**Pause
