import { Elysia } from "elysia";
import { createLibrary } from "./library";
import { requireKey } from "./shared/auth";
import type { Config } from "./shared/config";
import { openDatabase } from "./shared/db";
import { createSync } from "./sync";

/** Builds the whole HTTP app. Kept separate from `index.ts` so tests can call it. */
export function createApp(config: Config, db = openDatabase(config.dataDir)) {
  const library = createLibrary({ db, config });
  const sync = createSync({ db, config, library });

  const app = new Elysia({ serve: { maxRequestBodySize: 64 * 1024 ** 3 } })
    .onError(({ code, error }) => {
      if (code === "NOT_FOUND") return { error: "Not found" };
      if (code === "UNKNOWN" || code === "INTERNAL_SERVER_ERROR") console.error(error);
    })
    .get("/api/health", () => ({ ok: true }))
    .group("/api/server", (group) =>
      group.use(requireKey(config.accessKey)).get("", () => ({ name: config.name, uploadFolder: config.uploadFolder })),
    )
    .use(library.routes)
    .use(sync);

  return { app, library };
}
