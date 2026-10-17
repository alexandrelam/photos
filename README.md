# photos

Darkroom: back up your Android phone to a server at home, when you choose to, and browse the server's photo folders from the phone.

## Server

Needs [Bun](https://bun.sh). `ffmpeg` on the PATH is optional and enables video thumbnails.

```sh
bun install
PHOTOS_ROOT=/srv/photos bun run --filter server start
```

| Variable        | Default        | Meaning                                                    |
| --------------- | -------------- | ---------------------------------------------------------- |
| `PHOTOS_ROOT`   | `./photos`     | Folder the app can browse; uploads go inside it            |
| `UPLOAD_FOLDER` | `phone`        | Sub-folder of `PHOTOS_ROOT` for phone uploads              |
| `ACCESS_KEY`    | generated      | Key the app must send; printed at startup when generated   |
| `DATA_DIR`      | `./data`       | SQLite database and thumbnail cache                        |
| `PORT`          | `8080`         |                                                            |
| `SERVER_NAME`   | hostname       | Name shown in the app                                      |

On start the server indexes `PHOTOS_ROOT` and prints the access key. Files added to the folder by other means show up after **Settings → Rescan** in the app or a restart.

## Android app

Open `apps/android` in Android Studio, or build with `./gradlew assembleDebug`. On first launch enter the home address (e.g. `http://192.168.1.20:8080`), optionally a Tailscale address for when you're away, and the access key.

- **Photos**: everything on the phone and the server by day. **Sync** uploads the albums you chose; nothing uploads on its own.
- **Library**: pick which phone albums to back up, and browse the server's folders.
- **Settings**: Wi‑Fi only, only while charging, cache, server address.
