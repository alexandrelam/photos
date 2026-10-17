import { index, integer, sqliteTable, text, uniqueIndex } from "drizzle-orm/sqlite-core";

/** Every picture or video found under the photos root. */
export const media = sqliteTable(
  "media",
  {
    id: integer("id").primaryKey({ autoIncrement: true }),
    /** POSIX path relative to the photos root, e.g. `phone/Camera/2026/10/IMG_1.jpg`. */
    path: text("path").notNull(),
    /** Parent folder relative to the photos root, `""` for the root itself. */
    folder: text("folder").notNull(),
    name: text("name").notNull(),
    kind: text("kind", { enum: ["image", "video"] }).notNull(),
    size: integer("size").notNull(),
    mtimeMs: integer("mtime_ms").notNull(),
    /** Capture time in epoch ms (EXIF when available, file mtime otherwise). */
    takenAt: integer("taken_at").notNull(),
    width: integer("width"),
    height: integer("height"),
  },
  (t) => [
    uniqueIndex("media_path_idx").on(t.path),
    index("media_folder_idx").on(t.folder),
    index("media_taken_at_idx").on(t.takenAt, t.id),
  ],
);

/** Files a phone has uploaded, so a sync never sends the same file twice. */
export const uploads = sqliteTable(
  "uploads",
  {
    id: integer("id").primaryKey({ autoIncrement: true }),
    deviceId: text("device_id").notNull(),
    /** Stable key the phone computes for a local file. */
    sourceKey: text("source_key").notNull(),
    path: text("path").notNull(),
    size: integer("size").notNull(),
    uploadedAt: integer("uploaded_at").notNull(),
  },
  (t) => [uniqueIndex("uploads_source_idx").on(t.deviceId, t.sourceKey), index("uploads_path_idx").on(t.path)],
);
