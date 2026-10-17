# photos (Darkroom)

Monorepo for a self-hosted photo app: an Android client that backs up the phone's
pictures to a home server on demand, and lets you browse the server's folders.

```
apps/
  android/   Kotlin + Jetpack Compose app (Gradle project, open this folder in Android Studio)
  server/    Bun + Elysia API, Drizzle ORM on SQLite
```

Bun workspaces are declared in the root `package.json`; run `bun install` at the root.

## Commands

- Server: `bun run dev` (watch mode), `bun run test`, `bun run typecheck` from the root.
- New migration after editing `apps/server/src/shared/db/schema.ts`: `cd apps/server && bun run db:generate`.
- Android: `cd apps/android && ./gradlew assembleDebug testDebugUnitTest`.

## Product rules

- Sync is manual only: nothing uploads until the user taps **Sync**. Don't add background or automatic uploads.
- The server exposes one photos root (`PHOTOS_ROOT`); phone uploads land in `PHOTOS_ROOT/<UPLOAD_FOLDER>/<album>/<yyyy>/<mm>/`.
- Every API route except `/api/health` requires `Authorization: Bearer <ACCESS_KEY>`.
- The UI follows the "Darkroom" design (dark-first, amber accent `#FFB547`).

## Code organization

Build **deep, modular features with simple interfaces**. Keep the architecture straightforward and scalable without unnecessary abstractions or layers.

1. **Deep modules, simple APIs.** Modules hide internal complexity behind small public interfaces. Callers know _what_ to call (e.g. `theme.toggle()`, `auth.login()`), not _how_ it works. Pull complexity downward: handle edge cases, defaults and internals inside the module, not at the call site.
2. **Group by feature or domain concept** (e.g. `theme/`, `auth/`, `billing/`), not by file type (`controllers/`, `views/`). Keep a feature's components, state and helpers together in its folder.
3. **Strict module boundaries.** Every module exposes one public entry point (`index.ts`/`index.tsx`). Code outside a module imports only from that top-level index. Deep imports into another module's internal files are forbidden.
4. **Shared vs. feature.** UI primitives, core utilities and global setup live in a dedicated `shared/` folder.

### How this maps onto each app

- **Server** (`apps/server/src`): features are `library/` (indexing, folders, timeline, thumbnails, originals) and `sync/` (receiving uploads). Shared code is in `shared/config`, `shared/db`, `shared/auth`. Each folder's `index.ts` is its only entry point; `app.ts` wires them together.
- **Android** (`apps/android/app/src/main/java/io/github/alexandrelam/photos`): one package per feature (`server/`, `media/`, `sync/`, `photos/`, `library/`, `viewer/`, `settings/`, `connect/`), plus `shared/ui` and `shared/format`. Kotlin has no `index` file, so a feature's public API is its non-`internal` declarations, kept in the file named after the feature (`server/Server.kt`, `sync/Sync.kt`, `media/Media.kt`) or the screen it exposes. Everything else in the package is `internal` or `private` and must not be used from other packages. App-wide singletons live in `AppGraph` (`PhotosApp.kt`).
