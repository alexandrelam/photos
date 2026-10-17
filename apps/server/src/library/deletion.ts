import { and, eq, inArray } from "drizzle-orm";
import { rm } from "node:fs/promises";
import { join } from "node:path";
import { media, uploads, type Database } from "../shared/db";
import { insideRoot } from "./paths";

/**
 * Deletes files from disk and from the index. Targets are server ids and/or the
 * keys a device uploaded them under. Returns how many files were removed.
 */
export async function deleteMedia(
  db: Database,
  root: string,
  thumbsDir: string,
  target: { ids?: number[]; deviceId?: string; keys?: string[] },
) {
  const paths = new Set<string>();
  const ids = target.ids ?? [];
  for (let i = 0; i < ids.length; i += 500) {
    db.select({ path: media.path }).from(media).where(inArray(media.id, ids.slice(i, i + 500))).all().forEach((r) => paths.add(r.path));
  }
  const keys = target.keys ?? [];
  if (target.deviceId) {
    for (let i = 0; i < keys.length; i += 500) {
      db.select({ path: uploads.path })
        .from(uploads)
        .where(and(eq(uploads.deviceId, target.deviceId), inArray(uploads.sourceKey, keys.slice(i, i + 500))))
        .all()
        .forEach((r) => paths.add(r.path));
    }
  }

  let deleted = 0;
  for (const path of paths) {
    const absolute = insideRoot(root, path);
    if (!absolute) continue;
    const row = db.select({ id: media.id, mtimeMs: media.mtimeMs }).from(media).where(eq(media.path, path)).get();
    await rm(absolute, { force: true });
    if (row) await rm(join(thumbsDir, `${row.id}-${row.mtimeMs}.jpg`), { force: true });
    db.delete(media).where(eq(media.path, path)).run();
    db.delete(uploads).where(eq(uploads.path, path)).run();
    deleted++;
  }
  return deleted;
}
