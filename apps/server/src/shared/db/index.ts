import { Database as SQLite } from "bun:sqlite";
import { drizzle } from "drizzle-orm/bun-sqlite";
import { migrate } from "drizzle-orm/bun-sqlite/migrator";
import { join } from "node:path";
import * as schema from "./schema";

export { media, uploads } from "./schema";

export type Database = ReturnType<typeof openDatabase>;

/** Opens (or creates) the SQLite database in `dataDir` and applies pending migrations. */
export function openDatabase(dataDir: string | ":memory:") {
  const sqlite = new SQLite(dataDir === ":memory:" ? ":memory:" : join(dataDir, "photos.db"), { create: true });
  sqlite.exec("PRAGMA journal_mode = WAL;");
  const db = drizzle(sqlite, { schema });
  migrate(db, { migrationsFolder: join(import.meta.dir, "../../../drizzle") });
  return db;
}
