import { and, eq, inArray } from "drizzle-orm";
import { Elysia, t } from "elysia";
import { mkdir, rename, rm, utimes } from "node:fs/promises";
import { extname, join } from "node:path";
import type { Config } from "../shared/config";
import { requireKey } from "../shared/auth";
import { uploads, type Database } from "../shared/db";
import type { Library } from "../library";

/**
 * Receiving photos from phones. The phone asks which of its files are missing,
 * then uploads them one by one; nothing happens unless the phone starts it.
 */
export function createSync({ db, config, library }: { db: Database; config: Config; library: Library }) {
  return new Elysia({ prefix: "/api/sync" })
    .use(requireKey(config.accessKey))
    .post(
      "/check",
      ({ body }) => {
        const known = new Set<string>();
        for (let i = 0; i < body.keys.length; i += 500) {
          const chunk = body.keys.slice(i, i + 500);
          db.select({ key: uploads.sourceKey })
            .from(uploads)
            .where(and(eq(uploads.deviceId, body.deviceId), inArray(uploads.sourceKey, chunk)))
            .all()
            .forEach((row) => known.add(row.key));
        }
        return { missing: body.keys.filter((key) => !known.has(key)) };
      },
      { body: t.Object({ deviceId: t.String({ minLength: 1 }), keys: t.Array(t.String()) }) },
    )
    .put(
      "/upload",
      async ({ query, request, status }) => {
        const existing = db
          .select()
          .from(uploads)
          .where(and(eq(uploads.deviceId, query.deviceId), eq(uploads.sourceKey, query.key)))
          .get();
        if (existing) {
          await request.body?.cancel();
          return { path: existing.path, duplicate: true };
        }
        if (!request.body) return status(400, { error: "Missing file body" });

        const taken = new Date(query.takenAt);
        const folder = [
          config.uploadFolder,
          safeSegment(query.album) || "Camera",
          String(taken.getFullYear()),
          String(taken.getMonth() + 1).padStart(2, "0"),
        ].filter(Boolean).join("/");
        const absoluteFolder = join(config.photosRoot, folder);
        await mkdir(absoluteFolder, { recursive: true });

        const tmp = join(absoluteFolder, `.${crypto.randomUUID()}.uploading`);
        try {
          const written = await Bun.write(tmp, new Response(request.body));
          if (query.size !== undefined && written !== query.size) {
            return status(400, { error: `Upload incomplete: got ${written} of ${query.size} bytes` });
          }
          const name = await freeName(absoluteFolder, safeSegment(query.name) || `upload-${Date.now()}`);
          const absolute = join(absoluteFolder, name);
          await rename(tmp, absolute);
          await utimes(absolute, taken, taken).catch(() => {});

          const path = `${folder}/${name}`;
          db.insert(uploads)
            .values({ deviceId: query.deviceId, sourceKey: query.key, path, size: written, uploadedAt: Date.now() })
            .onConflictDoNothing()
            .run();
          const mediaId = await library.add(path);
          return { path, mediaId, duplicate: false };
        } finally {
          await rm(tmp, { force: true });
        }
      },
      {
        parse: "none",
        query: t.Object({
          deviceId: t.String({ minLength: 1 }),
          key: t.String({ minLength: 1 }),
          name: t.String({ minLength: 1 }),
          takenAt: t.Numeric(),
          album: t.Optional(t.String()),
          size: t.Optional(t.Numeric()),
        }),
      },
    );
}

/** One path segment with no separators or leading dots. */
function safeSegment(value: string | undefined) {
  return (value ?? "").replace(/[/\\\0]/g, "_").replace(/^\.+/, "").trim().slice(0, 200);
}

/** `IMG_1.jpg`, or `IMG_1 (2).jpg` when the name is taken. */
async function freeName(folder: string, name: string) {
  const ext = extname(name);
  const stem = name.slice(0, name.length - ext.length);
  for (let n = 1; ; n++) {
    const candidate = n === 1 ? name : `${stem} (${n})${ext}`;
    if (!(await Bun.file(join(folder, candidate)).exists())) return candidate;
  }
}
