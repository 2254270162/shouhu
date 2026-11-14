import { createHash, randomBytes, scryptSync, timingSafeEqual } from "node:crypto";
import { db, persist } from "./db.js";

/**
 * 管理员账号与会话。
 *
 * 密码用 scrypt 加盐哈希存储（Node 内置，无需额外依赖）。
 * 会话是随机 token + 服务端过期校验，存在库里，重启后仍有效。
 */
const SESSION_TTL_MS = 7 * 24 * 60 * 60 * 1000; // 7 天
const SCRYPT_KEYLEN = 64;

export function hashPassword(password, salt = randomBytes(16).toString("hex")) {
  const hash = scryptSync(password, salt, SCRYPT_KEYLEN).toString("hex");
  return { salt, hash };
}

export function verifyPassword(password, salt, expected) {
  const actual = scryptSync(password, salt, SCRYPT_KEYLEN);
  const expectedBuf = Buffer.from(expected, "hex");
  if (actual.length !== expectedBuf.length) return false;
  return timingSafeEqual(actual, expectedBuf);
}

/** 密码强度：至少 10 位，且不能是纯数字。 */
export function validatePassword(password) {
  if (typeof password !== "string") return "密码必须是字符串";
  if (password.length < 10) return "密码至少 10 位";
  if (password.length > 200) return "密码过长";
  if (/^\d+$/.test(password)) return "密码不能全是数字";
  return null;
}

export function createAdmin(username, password) {
  const name = String(username || "").trim();
  if (!/^[a-zA-Z0-9_.-]{3,32}$/.test(name)) {
    const err = new Error("用户名需为 3–32 位字母、数字、下划线或短横线");
    err.status = 400;
    throw err;
  }
  const weakness = validatePassword(password);
  if (weakness) {
    const err = new Error(weakness);
    err.status = 400;
    throw err;
  }
  if (db().admins[name]) {
    const err = new Error("该用户名已存在");
    err.status = 409;
    throw err;
  }
  const { salt, hash } = hashPassword(password);
  db().admins[name] = { username: name, salt, hash, createdAt: Date.now(), lastLoginAt: null };
  persist();
  return db().admins[name];
}

export function adminCount() {
  return Object.keys(db().admins).length;
}

export function findAdmin(username) {
  return db().admins[username] || null;
}

/** 登录成功则签发会话 token。 */
export function issueSession(username, ip) {
  const token = randomBytes(32).toString("base64url");
  db().adminSessions[token] = {
    token,
    username,
    createdAt: Date.now(),
    expiresAt: Date.now() + SESSION_TTL_MS,
    ip: ip || null,
  };
  const admin = db().admins[username];
  if (admin) admin.lastLoginAt = Date.now();
  persist();
  return { token, expiresAt: db().adminSessions[token].expiresAt };
}

export function getSession(token) {
  if (!token) return null;
  const s = db().adminSessions[token];
  if (!s) return null;
  if (Date.now() > s.expiresAt) {
    delete db().adminSessions[token];
    persist();
    return null;
  }
  return s;
}

export function revokeSession(token) {
  if (!token) return;
  delete db().adminSessions[token];
  persist();
}

/** 从 Cookie 或 Authorization 头里取出会话 token。 */
export function extractSessionToken(req) {
  const cookie = req.headers["cookie"] || "";
  const m = cookie.match(/(?:^|;\s*)guardian_session=([^;]+)/);
  if (m) return decodeURIComponent(m[1]);
  const auth = req.headers["authorization"];
  if (auth && auth.startsWith("Bearer ")) return auth.slice(7).trim();
  return null;
}

/** 简单的内存限速，防暴力破解。key -> { count, resetAt } */
const attempts = new Map();
const MAX_ATTEMPTS = 8;
const WINDOW_MS = 10 * 60 * 1000;

export function checkRateLimit(key) {
  const now = Date.now();
  const rec = attempts.get(key);
  if (!rec || now > rec.resetAt) {
    attempts.set(key, { count: 0, resetAt: now + WINDOW_MS });
    return { allowed: true, remaining: MAX_ATTEMPTS };
  }
  if (rec.count >= MAX_ATTEMPTS) {
    return { allowed: false, remaining: 0, retryAfterMs: rec.resetAt - now };
  }
  return { allowed: true, remaining: MAX_ATTEMPTS - rec.count };
}

export function recordFailure(key) {
  const rec = attempts.get(key);
  if (rec) rec.count += 1;
  else attempts.set(key, { count: 1, resetAt: Date.now() + WINDOW_MS });
}

export function clearFailures(key) {
  attempts.delete(key);
}

/** 会话 Cookie 头；HttpOnly 防止 XSS 读取。 */
export function sessionCookie(token, maxAgeMs) {
  const maxAge = Math.floor((maxAgeMs || SESSION_TTL_MS) / 1000);
  return `guardian_session=${encodeURIComponent(token)}; Path=/; HttpOnly; SameSite=Lax; Max-Age=${maxAge}`;
}

export function clearCookie() {
  return "guardian_session=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0";
}

export { SESSION_TTL_MS };
