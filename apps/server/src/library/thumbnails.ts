import { mkdir, rename, rm } from "node:fs/promises";
import { join } from "node:path";
import sharp from "sharp";

const SIZE = 512;
const ffmpeg = Bun.which("ffmpeg");

type Source = { id: number; kind: "image" | "video"; mtimeMs: number; absolute: string };

/**
 * Returns the path of a cached JPEG thumbnail for `source`, generating it on first
 * use. Returns null when the file cannot be decoded (e.g. a video without ffmpeg).
 */
export async function thumbnailFor(cacheDir: string, source: Source): Promise<string | null> {
  const target = join(cacheDir, `${source.id}-${source.mtimeMs}.jpg`);
  if (await Bun.file(target).exists()) return target;
  await mkdir(cacheDir, { recursive: true });

  const tmp = `${target}.${process.pid}.${Math.random().toString(36).slice(2)}.tmp`;
  try {
    const ok = source.kind === "image" ? await fromImage(source.absolute, tmp) : await fromVideo(source.absolute, tmp);
    if (!ok) return null;
    await rename(tmp, target);
    return target;
  } catch {
    return null;
  } finally {
    await rm(tmp, { force: true });
  }
}

async function fromImage(input: string, output: string) {
  await sharp(input, { failOn: "none" })
    .rotate()
    .resize(SIZE, SIZE, { fit: "inside", withoutEnlargement: true })
    .jpeg({ quality: 78, mozjpeg: true })
    .toFile(output);
  return true;
}

async function fromVideo(input: string, output: string) {
  if (!ffmpeg) return false;
  const frame = `${output}.png`;
  try {
    for (const seek of ["1", "0"]) {
      const proc = Bun.spawn([ffmpeg, "-v", "error", "-y", "-ss", seek, "-i", input, "-frames:v", "1", "-f", "image2", frame], {
        stdout: "ignore",
        stderr: "ignore",
      });
      if ((await proc.exited) === 0 && (await Bun.file(frame).exists())) {
        return fromImage(frame, output);
      }
    }
    return false;
  } finally {
    await rm(frame, { force: true });
  }
}
