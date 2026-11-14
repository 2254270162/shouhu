import { mkdirSync, readdirSync, statSync, existsSync, unlinkSync, createReadStream } from "node:fs";
import { join, basename } from "node:path";
import { writeFileSync } from "node:fs";

/**
 * APK 分发包托管。
 *
 * 用途：几十台 PDA 需要装同一个 App，不可能一台台连线。
 * 把 APK 传到服务端，生成一个局域网下载链接，PDA 浏览器打开即可安装。
 *
 * 安全设计：
 * - 分发包本身不含任何设备凭证，装完仍需各自配对，
 *   所以下载链接可以公开（同一局域网内），不构成风险；
 * - 上传/删除需要管理员登录，避免任意人替换安装包；
 * - 文件名做白名单校验，防止路径穿越。
 */
const APK_DIR = process.env.APK_DIR || join(process.env.DATA_DIR || join(process.cwd(), "data"), "apk");

const MAX_APK_BYTES = 300 * 1024 * 1024; // 300MB，足够容纳大型 APK

function ensureDir() {
  mkdirSync(APK_DIR, { recursive: true });
}

/**
 * 规范化文件名。
 *
 * 允许中文：构建产物用「被监控端-时间戳.apk」这种名字更直观，
 * 早期实现把非 ASCII 字符替换成下划线，导致构建出来的包下载 404。
 *
 * 仍然禁止：路径分隔符、控制字符、以点开头（隐藏文件），
 * 并要求 .apk 结尾。
 */
export function safeFileName(name) {
  let base = basename(String(name || ""));

  // 去掉控制字符与路径分隔符（basename 已处理分隔符，这里兜底）
  base = base.replace(/[\x00-\x1f\x7f/\\:*?"<>|]/g, "_").trim();

  if (!base) return null;
  if (base.startsWith(".")) return null;
  if (!base.toLowerCase().endsWith(".apk")) return null;

  // 长度限制，避免超长文件名在某些文件系统上失败
  if (base.length > 120) {
    const stamp = base.slice(-20);
    base = base.slice(0, 95) + stamp;
  }
  return base;
}

export function saveApk(filename, buffer) {
  ensureDir();
  const safe = safeFileName(filename);
  if (!safe) throw new Error("文件名不合法，必须是 .apk");
  if (buffer.length > MAX_APK_BYTES) {
    throw new Error(`安装包超过 ${(MAX_APK_BYTES / 1048576).toFixed(0)}MB 上限`);
  }
  // APK 是 zip，文件头必须是 PK\x03\x04
  if (!(buffer[0] === 0x50 && buffer[1] === 0x4b)) {
    throw new Error("不是有效的 APK 文件（应为 zip 格式）");
  }
  writeFileSync(join(APK_DIR, safe), buffer);
  return { name: safe, size: buffer.length };
}

export function listApks() {
  ensureDir();
  return readdirSync(APK_DIR)
    .filter((f) => f.toLowerCase().endsWith(".apk"))
    .map((f) => {
      const p = join(APK_DIR, f);
      const st = statSync(p);
      return { name: f, size: st.size, updatedAt: st.mtimeMs };
    })
    .sort((a, b) => b.updatedAt - a.updatedAt);
}

export function apkPath(name) {
  const safe = safeFileName(name);
  if (!safe) return null;
  const p = join(APK_DIR, safe);
  return existsSync(p) ? p : null;
}

export function openApkStream(name) {
  const p = apkPath(name);
  if (!p) return null;
  return { stream: createReadStream(p), size: statSync(p).size };
}

export function deleteApk(name) {
  const p = apkPath(name);
  if (!p) return false;
  try {
    unlinkSync(p);
    return true;
  } catch {
    return false;
  }
}
