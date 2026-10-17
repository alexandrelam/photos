import { and, desc, eq, lt, or, sql } from "drizzle-orm";
import { media, uploads, type Database } from "../shared/db";

/** What the app receives for each picture or video. */
export type MediaItem = {
  id: number;
  path: string;
  folder: string;
  name: string;
  kind: "image" | "video";
  size: number;
  takenAt: number;
  width: number | null;
  height: number | null;
  /** Set when a phone uploaded this file; lets the app match it with its local copy. */
  sourceKey: string | null;
};

const itemColumns = {
  id: media.id,
  path: media.path,
  folder: media.folder,
  name: media.name,
  kind: media.kind,
  size: media.size,
  takenAt: media.takenAt,
  width: media.width,
  height: media.height,
  sourceKey: uploads.sourceKey,
};

const newestFirst = [desc(media.takenAt), desc(media.id)];

/** Matches `folder` itself and everything below it. The root (`""`) matches everything. */
function underFolder(folder: string) {
  if (folder === "") return sql`1 = 1`;
  return or(eq(media.folder, folder), sql`substr(${media.folder}, 1, ${folder.length + 1}) = ${folder + "/"}`)!;
}

export function timelinePage(db: Database, cursor: { takenAt: number; id: number } | null, limit: number) {
  const items: MediaItem[] = db
    .select(itemColumns)
    .from(media)
    .leftJoin(uploads, eq(uploads.path, media.path))
    .where(
      cursor
        ? or(lt(media.takenAt, cursor.takenAt), and(eq(media.takenAt, cursor.takenAt), lt(media.id, cursor.id)))
        : undefined,
    )
    .orderBy(...newestFirst)
    .limit(limit)
    .all();
  const last = items.at(-1);
  return { items, next: items.length === limit && last ? `${last.takenAt}:${last.id}` : null };
}

export function folderItems(db: Database, folder: string, offset: number, limit: number) {
  const items: MediaItem[] = db
    .select(itemColumns)
    .from(media)
    .leftJoin(uploads, eq(uploads.path, media.path))
    .where(eq(media.folder, folder))
    .orderBy(...newestFirst)
    .limit(limit)
    .offset(offset)
    .all();
  const total = db.select({ n: sql<number>`count(*)` }).from(media).where(eq(media.folder, folder)).get()?.n ?? 0;
  return { items, total };
}

export function folderSummary(db: Database, folder: string) {
  const where = underFolder(folder);
  const stats = db
    .select({ count: sql<number>`count(*)`, oldest: sql<number | null>`min(${media.takenAt})`, newest: sql<number | null>`max(${media.takenAt})` })
    .from(media)
    .where(where)
    .get();
  const covers = db.select({ id: media.id }).from(media).where(where).orderBy(...newestFirst).limit(4).all();
  return {
    count: stats?.count ?? 0,
    oldest: stats?.oldest ?? null,
    newest: stats?.newest ?? null,
    coverIds: covers.map((c) => c.id),
  };
}

export function mediaById(db: Database, id: number) {
  return db.select().from(media).where(eq(media.id, id)).get() ?? null;
}

/**
 * Photo count per month, newest first. `offsetMinutes` is the phone's UTC offset so
 * months line up with what the phone shows. `mine` counts files uploaded by `deviceId`,
 * which the phone already counts as its own.
 */
export function monthCounts(db: Database, offsetMinutes: number, deviceId: string | undefined) {
  const shifted = sql`(${media.takenAt} / 1000) + ${offsetMinutes * 60}`;
  const month = sql<string>`strftime('%Y-%m', ${shifted}, 'unixepoch')`;
  return db
    .select({
      month,
      count: sql<number>`count(*)`,
      mine: sql<number>`count(${uploads.id})`,
    })
    .from(media)
    .leftJoin(uploads, and(eq(uploads.path, media.path), eq(uploads.deviceId, deviceId ?? "")))
    .groupBy(month)
    .orderBy(desc(month))
    .all();
}
