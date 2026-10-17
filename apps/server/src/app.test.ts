import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { mkdtemp, mkdir, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import sharp from "sharp";
import { createApp } from "./app";
import { loadConfig } from "./shared/config";

const KEY = "test-key";
let dir: string;
let app: ReturnType<typeof createApp>["app"];
let library: ReturnType<typeof createApp>["library"];

const req = (path: string, init: RequestInit = {}, key: string | null = KEY) =>
  app.handle(
    new Request(`http://local${path}`, {
      ...init,
      headers: { ...(key ? { authorization: `Bearer ${key}` } : {}), ...(init.headers as Record<string, string>) },
    }),
  );

const jpeg = (color: string) =>
  sharp({ create: { width: 64, height: 48, channels: 3, background: color } }).jpeg().toBuffer();

beforeAll(async () => {
  dir = await mkdtemp(join(tmpdir(), "photos-test-"));
  const root = join(dir, "photos");
  await mkdir(join(root, "Japan 2019/Kyoto"), { recursive: true });
  await Bun.write(join(root, "Japan 2019/a.jpg"), await jpeg("#e8b88a"));
  await Bun.write(join(root, "Japan 2019/Kyoto/b.jpg"), await jpeg("#33415c"));
  await Bun.write(join(root, "notes.txt"), "not a photo");
  ({ app, library } = createApp(
    loadConfig({ PHOTOS_ROOT: root, DATA_DIR: join(dir, "data"), ACCESS_KEY: KEY, SERVER_NAME: "nas" }),
  ));
  await library.rescan();
});

afterAll(() => rm(dir, { recursive: true, force: true }));

describe("auth", () => {
  test("health is public", async () => {
    expect((await req("/api/health", {}, null)).status).toBe(200);
  });

  test("every other route needs the key", async () => {
    for (const path of ["/api/server", "/api/timeline", "/api/folders", "/api/media/1/thumb", "/api/media/1/file"]) {
      expect((await req(path, {}, null)).status).toBe(401);
      expect((await req(path, {}, "wrong")).status).toBe(401);
    }
    const check = await req("/api/sync/check", { method: "POST" }, null);
    expect(check.status).toBe(401);
  });

  test("server info", async () => {
    expect(await (await req("/api/server")).json()).toEqual({ name: "nas", uploadFolder: "phone" });
  });
});

describe("library", () => {
  test("folders list subfolders with counts and covers", async () => {
    const root: any = await (await req("/api/folders")).json();
    expect(root.folders.map((f: { name: string }) => f.name)).toEqual(["Japan 2019"]);
    expect(root.folders[0].count).toBe(2);
    expect(root.folders[0].coverIds).toHaveLength(2);

    const japan: any = await (await req(`/api/folders?path=${encodeURIComponent("Japan 2019")}`)).json();
    expect(japan.folders.map((f: { name: string }) => f.name)).toEqual(["Kyoto"]);
    expect(japan.items.map((i: { name: string }) => i.name)).toEqual(["a.jpg"]);
    expect(japan.total).toBe(1);
  });

  test("folder paths cannot escape the root", async () => {
    expect((await req("/api/folders?path=../..")).status).toBe(400);
    expect((await req("/api/folders?path=missing")).status).toBe(404);
  });

  test("timeline pages through everything", async () => {
    const first: any = await (await req("/api/timeline?limit=1")).json();
    expect(first.items).toHaveLength(1);
    const second: any = await (await req(`/api/timeline?limit=1&cursor=${first.next}`)).json();
    expect(second.items).toHaveLength(1);
    expect(second.items[0].id).not.toBe(first.items[0].id);
  });

  test("thumbnails and ranged originals", async () => {
    const { items }: any = await (await req("/api/timeline")).json();
    const id = items[0].id;
    const thumb = await req(`/api/media/${id}/thumb`);
    expect(thumb.status).toBe(200);
    expect((await sharp(Buffer.from(await thumb.arrayBuffer())).metadata()).format).toBe("jpeg");

    const full = await req(`/api/media/${id}/file`);
    const size = (await full.arrayBuffer()).byteLength;
    const part = await req(`/api/media/${id}/file`, { headers: { range: "bytes=0-9" } });
    expect(part.status).toBe(206);
    expect(part.headers.get("content-range")).toBe(`bytes 0-9/${size}`);
    expect((await part.arrayBuffer()).byteLength).toBe(10);
  });
});

describe("sync", () => {
  const takenAt = new Date(2026, 9, 17, 18, 42).getTime();
  const upload = async (key: string, body: Uint8Array | Buffer, name = "IMG_2041.jpg") =>
    req(
      `/api/sync/upload?deviceId=pixel&key=${encodeURIComponent(key)}&name=${name}&album=Camera&takenAt=${takenAt}&size=${body.byteLength}`,
      { method: "PUT", body, headers: { "content-type": "application/octet-stream" } },
    );

  test("check, upload, then the file is known and browsable", async () => {
    const check = async (): Promise<any> =>
      (
        await req("/api/sync/check", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ deviceId: "pixel", keys: ["DCIM/Camera/IMG_2041.jpg:1", "other:2"] }),
        })
      ).json();
    expect((await check()).missing).toEqual(["DCIM/Camera/IMG_2041.jpg:1", "other:2"]);

    const res = await upload("DCIM/Camera/IMG_2041.jpg:1", await jpeg("#8aa0b8"));
    expect(res.status).toBe(200);
    const saved: any = await res.json();
    expect(saved.path).toBe("phone/Camera/2026/10/IMG_2041.jpg");
    expect(saved.mediaId).toBeNumber();

    expect((await check()).missing).toEqual(["other:2"]);

    const again: any = await (await upload("DCIM/Camera/IMG_2041.jpg:1", await jpeg("#8aa0b8"))).json();
    expect(again.duplicate).toBe(true);

    const folder: any = await (await req(`/api/folders?path=${encodeURIComponent("phone/Camera/2026/10")}`)).json();
    expect(folder.items[0].sourceKey).toBe("DCIM/Camera/IMG_2041.jpg:1");
  });

  test("a second file with the same name gets a free name", async () => {
    const saved: any = await (await upload("DCIM/Other/IMG_2041.jpg:9", await jpeg("#111111"))).json();
    expect(saved.path).toBe("phone/Camera/2026/10/IMG_2041 (2).jpg");
  });

  test("truncated uploads are rejected", async () => {
    const res = await req(
      `/api/sync/upload?deviceId=pixel&key=short&name=x.jpg&takenAt=${takenAt}&size=999`,
      { method: "PUT", body: new Uint8Array([1, 2, 3]) },
    );
    expect(res.status).toBe(400);
  });
});

describe("months and delete", () => {
  test("months count everything and what this device uploaded", async () => {
    const { months }: any = await (await req("/api/timeline/months?deviceId=pixel&offsetMinutes=120")).json();
    const total = months.reduce((n: number, m: any) => n + m.count, 0);
    const mine = months.reduce((n: number, m: any) => n + m.mine, 0);
    expect(total).toBe(4);
    expect(mine).toBe(2);
    expect(months.find((m: any) => m.month === "2026-10")?.mine).toBe(2);
  });

  test("delete by device key and by id", async () => {
    const del = (body: object) =>
      req("/api/media/delete", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) });
    const byKey: any = await (await del({ deviceId: "pixel", keys: ["DCIM/Camera/IMG_2041.jpg:1"] })).json();
    expect(byKey.deleted).toBe(1);
    expect(await Bun.file(join(dir, "photos/phone/Camera/2026/10/IMG_2041.jpg")).exists()).toBe(false);

    const { items }: any = await (await req("/api/timeline")).json();
    const kyoto = items.find((i: any) => i.name === "b.jpg");
    const byId: any = await (await del({ ids: [kyoto.id] })).json();
    expect(byId.deleted).toBe(1);
    const after: any = await (await req("/api/timeline")).json();
    expect(after.items.map((i: any) => i.name).sort()).toEqual(["IMG_2041 (2).jpg", "a.jpg"]);
  });
});
