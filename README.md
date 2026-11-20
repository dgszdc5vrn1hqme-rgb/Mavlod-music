# K Music

An offline music player for Android. Import songs from your phone and play them with
the built-in player: equalizer, lyrics, playlists, car mode. No server, no account.

## Get the app (GitHub builds it for you)

1. Create a free account on github.com and make a **new repository** (any name, Public or Private).
2. Upload everything from this folder to it, including the hidden `.github` folder
   (on the repository page: Add file > Upload files, drag the contents in, then Commit).
3. Open the **Actions** tab. The **Build APK** job starts by itself. Wait about 5 minutes for the green check.
4. Open the **Releases** section on the repository's main page and download `K-Music.apk`.
5. Open the file on your phone and install it. If asked, allow "Install unknown apps".

Every time you change something and commit, GitHub builds a new APK and replaces the
one in Releases. New builds install over the old one and keep your imported music.

If the build fails, open the failed run in Actions, copy the red error text and send it to Claude.

## What it does
- Add files from the phone, or paste a direct audio link, or use **Find free music** (Internet Archive): search, see cover photos, tap Songs, then Download one song or the whole album.
- Cover photos show in your library, the mini player and the spinning disc. They come from the song file itself or from the search result, and are saved on the phone so they work offline.
- Synced lyrics are fetched online (LRCLIB) and cached for offline use.
- Background playback, notification and headset controls, equalizer, playlists, car mode.

## Download section
- Search free music (MP3) and video (MP4) from the Internet Archive, preview before saving, pick the quality.
- Downloads tab: progress, speed, time left, pause, resume, cancel, pause all / resume all.
- Videos are saved to Movies/K Music. Songs go into your library.
- "On phone" tab lists every video on the phone; phone music is added to the library automatically.
- Links from YouTube, TikTok, Spotify and similar sites are not supported.

## Sorting and lists
Home, Liked and All songs have a **Sort** button: Recently added, Title A–Z, Artist A–Z, Most played, Longest, Shortest
(saved on the phone). Next and Previous follow the order you see. Long-press a song for Play now and Delete.

## Sound studio
Open it from the Equalizer sheet (Library > Equalizer > Adjust > Sound studio) or the clock button on the player.
Balance left/right, stereo width and mono, bass boost, loudness with a limiter, volume levelling between songs,
reverb (Room, Hall, Plate), speed with pitch lock, fades between songs, a real sleep timer, and quick setups
(Car, Headphones, Speaker, Night, Bass+, Live hall). Settings are saved on the phone.

## Android Auto
Plug the phone into the car (or use Android Auto wireless). K Music appears in the media apps: browse
Recently added, Liked songs, Artists and All songs, play, pause, skip, or say "Play <song> on K Music".
Because this app is not from the Play Store, turn on Android Auto's developer mode once:
Android Auto settings > tap "Version" 10 times > menu (three dots) > Developer settings > Unknown sources.
Keep K Music open on the phone while driving (it plays the audio).

## Optional: exact fonts
Put `Outfit.ttf` and `Cairo.ttf` (variable fonts) in `app/src/main/assets/www/fonts/`.

## Notes
- Needs Android 10 or newer.
- `app/kmusic.jks` is a signing key for personal use. Anyone with the repository can see it,
  so for Play Store publishing, create your own private key and store it in GitHub Secrets.
- The web app lives in `app/src/main/assets/www/`. Edit `index.html` to change the app.
