import { Elysia, t } from "elysia";
import { readdir } from "node:fs/promises";
import { join } from "node:path";
import type { Config } from "../shared/config";
import type { Database } from "../shared/db";
import { requireKey } from "../shared/auth";
import { cleanRelative, insideRoot } from "./paths";
import { folderItems, folderSummary, mediaById, timelinePage } from "./queries";
import { indexFile, scanLibrary, type ScanResult } from "./scanner";
import { thumbnailFor } from "./thumbnails";

export type { MediaItem } from "./queries";
export type { ScanResult } from "./scanner";

export type Library = ReturnType<typeof createLibrary>;

/**
 * Everything about the pictures under the photos root: indexing, browsing
 * folders, the timeline, thumbnails and original files.
 */
export function createLibrary({ db, config }: { db: Database; config: Config }) {
  const root = config.photosRoot;
  const thumbsDir = join(config.dataDir, "thumbs");
  let running: Promise<ScanResult> | null = null;

  /** Re-indexes the photos root. Concurrent calls share the same scan. */
  function rescan() {
    running ??= scanLibrary(db, root, [config.dataDir]).finally(() => (running = null));
    return running;
  }

  /** Indexes a single file (relative to the photos root) right away. */
  function add(relativePath: string) {
    return indexFile(db, root, relativePath);
  }

  const routes = new Elysia({ prefix: "/api" })
    .use(requireKey(config.accessKey))
    .get(
      "/timeline",
      ({ query }) => {
        const [takenAt, id] = (query.cursor ?? "").split(":").map(Number);
        const cursor = Number.isFinite(takenAt) && Number.isFinite(id) && query.cursor ? { takenAt: takenAt!, id: id! } : null;
        return timelinePage(db, cursor, clamp(query.limit, 1, 500, 200));
      },
      { query: t.Object({ cursor: t.Optional(t.String()), limit: t.Optional(t.Numeric()) }) },
    )
    .get(
      "/folders",
      async ({ query, status }) => {
        const folder = cleanRelative(query.path);
        const absolute = folder === null ? null : insideRoot(root, folder);
        if (folder === null || !absolute) return status(400, { error: "Invalid path" });

        const entries = await readdir(absolute, { withFileTypes: true }).catch(() => null);
        if (!entries) return status(404, { error: "Folder not found" });

        const folders = entries
          .filter((e) => e.isDirectory() && !e.name.startsWith(".") && join(absolute, e.name) !== config.dataDir)
          .map((e) => {
            const path = folder ? `${folder}/${e.name}` : e.name;
            return { path, name: e.name, ...folderSummary(db, path) };
          })
          .sort((a, b) => a.name.localeCompare(b.name));

        const page = folderItems(db, folder, clamp(query.offset, 0, Number.MAX_SAFE_INTEGER, 0), clamp(query.limit, 1, 1000, 300));
        return { path: folder, name: folder.split("/").at(-1) || config.name, folders, ...page };
      },
      { query: t.Object({ path: t.Optional(t.String()), offset: t.Optional(t.Numeric()), limit: t.Optional(t.Numeric()) }) },
    )
    .get(
      "/media/:id/thumb",
      async ({ params, status, set }) => {
        const item = mediaById(db, params.id);
        const absolute = item && insideRoot(root, item.path);
        if (!item || !absolute) return status(404, { error: "Not found" });
        const thumb = await thumbnailFor(thumbsDir, { ...item, absolute });
        if (!thumb) return status(404, { error: "No thumbnail" });
        set.headers["cache-control"] = "private, max-age=31536000, immutable";
        return Bun.file(thumb);
      },
      { params: t.Object({ id: t.Numeric() }) },
    )
    .get(
      "/media/:id/file",
      async ({ params, headers, status }) => {
        const item = mediaById(db, params.id);
        const absolute = item && insideRoot(root, item.path);
        const file = absolute ? Bun.file(absolute) : null;
        if (!item || !file || !(await file.exists())) return status(404, { error: "Not found" });
        return serveFile(file, item.name, headers.range);
      },
      { params: t.Object({ id: t.Numeric() }) },
    )
    .post("/library/rescan", () => rescan());

  return { routes, rescan, add };
}

/** Serves a file with HTTP range support so videos can be streamed and seeked. */
function serveFile(file: ReturnType<typeof Bun.file>, name: string, range: string | undefined) {
  const size = file.size;
  const base = {
    "accept-ranges": "bytes",
    "content-type": file.type,
    "content-disposition": `inline; filename*=UTF-8''${encodeURIComponent(name)}`,
  };
  const match = range?.match(/^bytes=(\d*)-(\d*)$/);
  if (!match || (match[1] === "" && match[2] === "")) {
    return new Response(file, { headers: { ...base, "content-length": String(size) } });
  }
  let start = match[1] === "" ? size - Number(match[2]) : Number(match[1]);
  let end = match[1] === "" || match[2] === "" ? size - 1 : Math.min(Number(match[2]), size - 1);
  start = Math.max(0, start);
  if (start > end || start >= size) {
    return new Response(null, { status: 416, headers: { ...base, "content-range": `bytes */${size}` } });
  }
  return new Response(file.slice(start, end + 1), {
    status: 206,
    headers: { ...base, "content-range": `bytes ${start}-${end}/${size}`, "content-length": String(end - start + 1) },
  });
}

function clamp(value: number | undefined, min: number, max: number, fallback: number) {
  if (value === undefined || Number.isNaN(value)) return fallback;
  return Math.min(max, Math.max(min, Math.floor(value)));
}
