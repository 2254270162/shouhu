import { createHash, randomBytes, timingSafeEqual } from "node:crypto";

/** 生成不易猜测的随机串，用于设备令牌与配对码。 */
export function randomToken(bytes = 32) {
  return randomBytes(bytes).toString("base64url");
}

/** 配对码：6 位数字，人眼易读、手输方便。 */
export function pairingCode() {
  return String(Math.floor(100000 + Math.random() * 900000));
}

/**
 * 监控端 Key 只存哈希，库被读走也无法还原明文。
 * 对固定长度摘要可直接用 timingSafeEqual，避免逐字符比较的时序泄露。
 */
export function hashKey(key) {
  return createHash("sha256").update(key, "utf8").digest("hex");
}

export function safeEqual(a, b) {
  const bufA = Buffer.from(String(a));
  const bufB = Buffer.from(String(b));
  if (bufA.length !== bufB.length) return false;
  return timingSafeEqual(bufA, bufB);
}

/** 从 Authorization: Bearer xxx 或 ?key= 提取 Key。 */
export function extractKey(req, url) {
  const auth = req.headers["authorization"];
  if (auth && auth.startsWith("Bearer ")) return auth.slice(7).trim();
  const q = url?.searchParams?.get("key");
  if (q) return q.trim();
  return null;
}

/** 设备令牌同样走 Bearer，但放在单独的请求头，避免与监控端 Key 混淆。 */
export function extractDeviceToken(req, url) {
  const t = req.headers["x-device-token"];
  if (t) return String(t).trim();
  const q = url?.searchParams?.get("deviceToken");
  if (q) return q.trim();
  return null;
}

/**
 * 校验监控端 Key。
 *
 * 设计变更说明：早期实现只认"已在某台设备上登记的 Key"，导致新用户第一台设备
 * 无法配对（还没有任何设备，Key 无处登记）。改为显式注册表：
 * - Key 第一次出现时自动注册（监控端 init 或任何带 Key 的请求）；
 * - 注册后即可生成配对码，与是否已有设备解耦。
 */
export function keyHashOf(key) {
  return hashKey(key);
}

/** Key 合法性：长度与字符集约束，避免弱口令。 */
export function validateKeyStrength(key) {
  if (typeof key !== "string") return "Key 必须是字符串";
  if (key.length < 12) return "Key 至少 12 位";
  if (key.length > 128) return "Key 过长";
  return null;
}

