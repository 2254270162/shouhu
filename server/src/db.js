// 轻量持久化：直接读写 JSON 文件，避免引入原生依赖（服务器上无需编译 sqlite）
import { mkdirSync, readFileSync, writeFileSync, existsSync, renameSync } from "node:fs";
import { dirname, join } from "node:path";

const DATA_DIR = process.env.DATA_DIR || join(process.cwd(), "data");
const DB_FILE = join(DATA_DIR, "db.json");

const empty = () => ({
  /** Key 哈希 -> { hash, createdAt, lastUsedAt, label }，与设备解耦 */
  monitors: {},
  /** 配对码 -> { code, ownerKeyHash, createdAt, expiresAt } */
  pairingCodes: {},
  /** deviceId -> { id, name, ownerKeyHash, ownerKey, token, ... } */
  devices: {},
  /** 管理员账号 -> { username, salt, hash, createdAt, lastLoginAt, mustChangePassword } */
  admins: {},
  /** 管理员会话 token -> { token, username, createdAt, expiresAt, ip } */
  adminSessions: {},
  /** 设备分组 -> { id, name, note, color, createdAt } */
  groups: {},
});

function load() {
  if (!existsSync(DB_FILE)) return empty();
  try {
    const raw = readFileSync(DB_FILE, "utf8");
    const parsed = JSON.parse(raw);
    return { ...empty(), ...parsed };
  } catch (e) {
    console.error("[db] 读取失败，使用空库:", e.message);
    return empty();
  }
}

let state = load();
let saveTimer = null;

/** 合并写盘：短时间内多次变更只写一次，避免照片批量上传时抖动 */
export function persist() {
  if (saveTimer) return;
  saveTimer = setTimeout(() => {
    saveTimer = null;
    try {
      mkdirSync(dirname(DB_FILE), { recursive: true });
      const tmp = DB_FILE + ".tmp";
      writeFileSync(tmp, JSON.stringify(state, null, 2), "utf8");
      renameSync(tmp, DB_FILE); // 原子替换，避免写一半断电损坏
    } catch (e) {
      console.error("[db] 写盘失败:", e.message);
    }
  }, 200);
}

export function db() {
  return state;
}

export function resetForTest() {
  state = empty();
  persist();
}
