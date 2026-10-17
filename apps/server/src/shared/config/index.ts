import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { hostname } from "node:os";
import { join, resolve } from "node:path";
import { randomBytes } from "node:crypto";

export type Config = {
  /** Server name shown in the app. */
  name: string;
  port: number;
  /** Folder whose pictures the app can browse. */
  photosRoot: string;
  /** Folder (relative to photosRoot) where phone uploads land. */
  uploadFolder: string;
  /** Where the database and thumbnail cache live. */
  dataDir: string;
  accessKey: string;
};

/**
 * Reads the configuration from the environment. Creates the data folder and,
 * when ACCESS_KEY is unset, a persistent random access key inside it.
 */
export function loadConfig(rawEnv: Record<string, string | undefined> = process.env): Config {
  // Treat empty variables (e.g. `ACCESS_KEY=` from docker compose) as unset.
  const env = Object.fromEntries(Object.entries(rawEnv).filter(([, v]) => v !== undefined && v !== ""));
  const dataDir = resolve(env.DATA_DIR ?? "./data");
  const photosRoot = resolve(env.PHOTOS_ROOT ?? "./photos");
  mkdirSync(dataDir, { recursive: true });
  mkdirSync(photosRoot, { recursive: true });

  return {
    name: env.SERVER_NAME ?? hostname(),
    port: Number(env.PORT ?? 8080),
    photosRoot,
    uploadFolder: (env.UPLOAD_FOLDER ?? "phone").replace(/^\/+|\/+$/g, ""),
    dataDir,
    accessKey: env.ACCESS_KEY ?? persistentKey(join(dataDir, "access-key")),
  };
}

function persistentKey(file: string): string {
  if (existsSync(file)) return readFileSync(file, "utf8").trim();
  const key = randomBytes(24).toString("base64url");
  writeFileSync(file, key + "\n", { mode: 0o600 });
  return key;
}
