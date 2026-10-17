import { extname, posix, relative, resolve, sep } from "node:path";

const IMAGE_EXTENSIONS = new Set([".jpg", ".jpeg", ".png", ".webp", ".gif", ".heic", ".heif", ".avif", ".tif", ".tiff"]);
const VIDEO_EXTENSIONS = new Set([".mp4", ".mov", ".m4v", ".3gp", ".mkv", ".webm"]);

export type MediaKind = "image" | "video";

export function mediaKind(fileName: string): MediaKind | null {
  const ext = extname(fileName).toLowerCase();
  if (IMAGE_EXTENSIONS.has(ext)) return "image";
  if (VIDEO_EXTENSIONS.has(ext)) return "video";
  return null;
}

/** Normalizes a client-supplied relative path: POSIX separators, no leading/trailing slashes, no `..`. */
export function cleanRelative(path: string | undefined): string | null {
  const slashed = (path ?? "").replaceAll("\\", "/");
  if (slashed.split("/").some((part) => part === "..")) return null;
  return posix.normalize(`/${slashed}`).replace(/^\/+|\/+$/g, "");
}

/** Resolves `rel` inside `root`, or returns null when it would escape it. */
export function insideRoot(root: string, rel: string): string | null {
  const absolute = resolve(root, rel);
  const back = relative(root, absolute);
  if (back.startsWith("..") || resolve(root, back) !== absolute) return null;
  return absolute;
}

/** Turns an absolute path inside `root` into the POSIX relative form stored in the database. */
export function toRelative(root: string, absolute: string): string {
  return relative(root, absolute).split(sep).join("/");
}

export function parentFolder(rel: string): string {
  const i = rel.lastIndexOf("/");
  return i === -1 ? "" : rel.slice(0, i);
}
