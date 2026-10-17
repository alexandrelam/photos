import { Elysia } from "elysia";
import { timingSafeEqual } from "node:crypto";

/**
 * Rejects every request that does not carry `Authorization: Bearer <key>`.
 * Put `.use(requireKey(key))` before the routes it should protect.
 */
export function requireKey(accessKey: string) {
  const expected = Buffer.from(accessKey);
  // Runs at transform time so a bad key is rejected before body validation.
  return new Elysia().onTransform({ as: "scoped" }, ({ headers, status }) => {
    const given = Buffer.from(headers.authorization?.replace(/^Bearer\s+/i, "") ?? "");
    if (given.length !== expected.length || !timingSafeEqual(given, expected)) {
      throw status(401, { error: "Invalid access key" });
    }
  });
}
