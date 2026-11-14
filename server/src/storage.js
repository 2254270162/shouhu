import { mkdirSync, writeFileSync, readFileSync, existsSync } from "node:fs";
import { join } from "node:path";
import { createHash } from "node:crypto";
import { sniffPhoto } from "./imageType.js";

const PHOTO_DIR = process.env.PHOTO_DIR || join(process.cwd(), "data", "photos");
const KNOWN_EXTS = ["jpg", "png", "webp", "gif", "bmp", "heic"];

function safeName(s) {
  return String(s || "").replace(/[^a-zA-Z0-9_-]/g, "_");
}

export function photoPath(deviceId, photoId, ext = "jpg") {
  const dir = join(PHOTO_DIR, safeName(deviceId));
  mkdirSync(dir, { recursive: true });
  return join(dir, `${safeName(photoId)}.${ext}`);
}

export function savePhoto(deviceId, photoId, buffer, ext = "jpg") {
  const p = photoPath(deviceId, photoId, ext);
  writeFileSync(p, buffer);
  return { path: p, sha256: createHash("sha256").update(buffer).digest("hex"), size: buffer.length };
}

/**
 * 读取照片，返回内容与真实 MIME。
 *
 * 磁盘上的文件名可能来自旧版本（一律 .jpg），所以找不到时
 * 按已知扩展名逐个尝试，而不是直接判为不存在。
 */
export function readPhoto(deviceId, photoId, ext) {
  const order = ext ? [ext, ...KNOWN_EXTS.filter((e) => e !== ext)] : KNOWN_EXTS;
  for (const e of order) {
    const p = photoPath(deviceId, photoId, e);
    if (existsSync(p)) {
      const buf = readFileSync(p);
      const kind = sniffPhoto(buf);
      return { buf, mime: kind.mime, ext: e };
    }
  }
  return null;
}

export function photoExists(deviceId, photoId, ext = "jpg") {
  return existsSync(photoPath(deviceId, photoId, ext));
}
