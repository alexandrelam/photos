import { eq, inArray } from "drizzle-orm";
import exifr from "exifr";
import { readdir, stat } from "node:fs/promises";
import { basename, join } from "node:path";
import { media, type Database } from "../shared/db";
import { insideRoot, mediaKind, parentFolder, toRelative } from "./paths";

export type ScanResult = { added: number; updated: number; removed: number; total: number };

/**
 * Keeps the `media` table in step with the files under `root`. Unchanged files
 * (same size and mtime) are skipped, so rescans of a large library are cheap.
 */
export async function scanLibrary(db: Database, root: string, skip: string[] = []): Promise<ScanResult> {
  const known = new Map(
    db.select({ id: media.id, path: media.path, size: media.size, mtimeMs: media.mtimeMs }).from(media).all().map((row) => [row.path, row]),
  );
  const seen = new Set<string>();
  const result: ScanResult = { added: 0, updated: 0, removed: 0, total: 0 };

  for await (const absolute of walk(root, new Set(skip))) {
    const rel = toRelative(root, absolute);
    seen.add(rel);
    const info = await stat(absolute).catch(() => null);
    if (!info) continue;
    const existing = known.get(rel);
    if (existing && existing.size === info.size && existing.mtimeMs === Math.floor(info.mtimeMs)) continue;
    await indexFile(db, root, rel);
    if (existing) result.updated++;
    else result.added++;
  }

  const gone = [...known.values()].filter((row) => !seen.has(row.path)).map((row) => row.id);
  for (let i = 0; i < gone.length; i += 500) {
    db.delete(media).where(inArray(media.id, gone.slice(i, i + 500))).run();
  }
  result.removed = gone.length;
  result.total = seen.size;
  return result;
}

/** Reads one file's metadata and inserts or refreshes its row. Returns the row id, or null if it is not media. */
export async function indexFile(db: Database, root: string, rel: string): Promise<number | null> {
  const kind = mediaKind(rel);
  const absolute = insideRoot(root, rel);
  if (!kind || !absolute) return null;
  const info = await stat(absolute).catch(() => null);
  if (!info?.isFile()) return null;

  const exif = kind === "image" ? await readExif(absolute) : null;
  const row = {
    path: rel,
    folder: parentFolder(rel),
    name: basename(rel),
    kind,
    size: info.size,
    mtimeMs: Math.floor(info.mtimeMs),
    takenAt: exif?.takenAt ?? Math.floor(info.mtimeMs),
    width: exif?.width ?? null,
    height: exif?.height ?? null,
  };
  const saved = db
    .insert(media)
    .values(row)
    .onConflictDoUpdate({ target: media.path, set: row })
    .returning({ id: media.id })
    .get();
  return saved?.id ?? db.select({ id: media.id }).from(media).where(eq(media.path, rel)).get()?.id ?? null;
}

const EXIF_HEAD = 256 * 1024;

/**
 * Hands exifr bytes rather than a path: given a path it opens a FileHandle that it
 * does not always close, and Bun crashes the process when such a handle is collected.
 * The head of the file holds the EXIF block for most photos; the whole file is read
 * only when the head yields no date (e.g. HEIF that stores it further in).
 */
async function readExif(path: string) {
  const file = Bun.file(path);
  try {
    const head = await parseExif(await file.slice(0, EXIF_HEAD).arrayBuffer());
    if (head?.takenAt !== undefined || file.size <= EXIF_HEAD) return head;
    return (await parseExif(await file.arrayBuffer())) ?? head;
  } catch {
    return null;
  }
}

async function parseExif(bytes: ArrayBuffer) {
  try {
    const tags = await exifr.parse(bytes, {
      pick: ["DateTimeOriginal", "CreateDate", "ExifImageWidth", "ExifImageHeight", "ImageWidth", "ImageHeight", "Orientation"],
      translateValues: false,
    });
    if (!tags) return null;
    const date: unknown = tags.DateTimeOriginal ?? tags.CreateDate;
    let width: number | undefined = tags.ExifImageWidth ?? tags.ImageWidth;
    let height: number | undefined = tags.ExifImageHeight ?? tags.ImageHeight;
    // Orientations 5-8 are rotated by 90°, so the displayed size is swapped.
    if (typeof tags.Orientation === "number" && tags.Orientation >= 5) [width, height] = [height, width];
    return {
      takenAt: date instanceof Date && !Number.isNaN(date.getTime()) ? date.getTime() : undefined,
      width,
      height,
    };
  } catch {
    return null;
  }
}

async function* walk(dir: string, skip: Set<string>): AsyncGenerator<string> {
  const entries = await readdir(dir, { withFileTypes: true }).catch(() => []);
  for (const entry of entries) {
    if (entry.name.startsWith(".")) continue;
    const absolute = join(dir, entry.name);
    if (skip.has(absolute)) continue;
    if (entry.isDirectory()) yield* walk(absolute, skip);
    else if (entry.isFile() && mediaKind(entry.name)) yield absolute;
  }
}
