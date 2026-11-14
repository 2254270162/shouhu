import {
  appendFileSync,
  mkdirSync,
  existsSync,
  readFileSync,
  readdirSync,
  statSync,
  unlinkSync,
  writeFileSync,
} from "node:fs";
import { join } from "node:path";

/**
 * 每台设备一份独立日志。
 *
 * 设计取舍：
 * - 用 JSONL（每行一条 JSON）而不是塞进 db.json：日志是持续增长的，
 *   放进主库会让每次持久化都要重写全部日志，设备一多就卡。
 * - 按设备分文件：查看某台设备的日志不需要读别人的，也便于单独清理。
 * - 只保留最近 N 条（轮转）：备用机可能跑几个月，日志无上限会吃满磁盘。
 */
const LOG_DIR = process.env.LOG_DIR || join(process.env.DATA_DIR || join(process.cwd(), "data"), "logs");
const MAX_LOG_BYTES = 2 * 1024 * 1024; // 单设备日志上限 2MB
const MAX_LOG_LINES = 3000; // 轮转后保留条数

function logFile(deviceId) {
  const safe = String(deviceId || "unknown").replace(/[^a-zA-Z0-9_-]/g, "_");
  return join(LOG_DIR, `${safe}.jsonl`);
}

export const LogLevel = {
  INFO: "info",
  WARN: "warn",
  ERROR: "error",
};

/**
 * 写一条日志。
 * @param {string} deviceId
 * @param {string} event  事件类型，如 online / offline / locate / capture / command / error
 * @param {string} message 人读的说明
 * @param {object} [detail] 结构化的附加数据
 */
export function logEvent(deviceId, event, message, detail = null, level = LogLevel.INFO) {
  const entry = {
    ts: Date.now(),
    event,
    level,
    message,
    detail: detail || null,
  };
  try {
    mkdirSync(LOG_DIR, { recursive: true });
    const file = logFile(deviceId);
    appendFileSync(file, JSON.stringify(entry) + "\n", "utf8");
    rotateIfNeeded(file);
  } catch (e) {
    console.error("[log] 写入失败:", e.message);
  }
  return entry;
}

/** 超过大小上限就保留最近若干行，避免单文件无限增长。 */
function rotateIfNeeded(file) {
  try {
    const size = statSync(file).size;
    if (size <= MAX_LOG_BYTES) return;
    const lines = readFileSync(file, "utf8").split("\n").filter(Boolean);
    const kept = lines.slice(-MAX_LOG_LINES);
    writeFileSync(file, kept.join("\n") + "\n", "utf8");
  } catch {
    /* 轮转失败不影响写入 */
  }
}

/**
 * 读取某台设备的日志（最新的在前）。
 * @param {string} deviceId
 * @param {{limit?: number, since?: number, event?: string}} opts
 */
export function readLogs(deviceId, { limit = 200, since = 0, event = null } = {}) {
  const file = logFile(deviceId);
  if (!existsSync(file)) return [];
  try {
    const lines = readFileSync(file, "utf8").split("\n").filter(Boolean);
    const out = [];
    // 从后往前读，取最新的 limit 条，避免大文件全量解析
    for (let i = lines.length - 1; i >= 0 && out.length < limit; i--) {
      if (!lines[i]) continue;
      let e;
      try {
        e = JSON.parse(lines[i]);
      } catch {
        continue;
      }
      if (since && e.ts < since) break;
      if (event && e.event !== event) continue;
      out.push(e);
    }
    return out;
  } catch (e) {
    console.error("[log] 读取失败:", e.message);
    return [];
  }
}

/** 清空某台设备的日志；设备被解绑时调用。 */
export function clearLogs(deviceId) {
  const file = logFile(deviceId);
  try {
    if (existsSync(file)) unlinkSync(file);
    return true;
  } catch {
    return false;
  }
}

/** 列出所有有日志的设备，供排查使用。 */
export function listLogDevices() {
  try {
    if (!existsSync(LOG_DIR)) return [];
    return readdirSync(LOG_DIR)
      .filter((f) => f.endsWith(".jsonl"))
      .map((f) => ({
        deviceId: f.replace(/\.jsonl$/, ""),
        bytes: statSync(join(LOG_DIR, f)).size,
      }));
  } catch {
    return [];
  }
}
