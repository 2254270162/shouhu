import { closeSync, existsSync, mkdirSync, openSync, readSync, statSync, unlinkSync, writeSync } from "node:fs";
import { dirname, join } from "node:path";
import { execFileSync } from "node:child_process";

/**
 * 读取 exe 尾部附加的资源归档，并按需释放到磁盘。
 *
 * 打包端的格式约定（见 bundle-resources.mjs）：
 *   [exe][归档数据…][索引 JSON][索引长度 8 字节][魔数 16 字节]
 *
 * 这里只读尾部 24 字节拿索引位置，再读索引，最后按偏移解单个归档。
 * 不需要把 2GB 全部读进内存。
 */

const MAGIC = "GUARDIANRES00001";
const FOOTER_SIZE = 24;

/** 判断当前 exe 是否附带资源。 */
export function hasBundledResources(exePath) {
  try {
    const size = statSync(exePath).size;
    if (size < FOOTER_SIZE) return false;

    const buf = Buffer.alloc(FOOTER_SIZE);
    const fd = openSync(exePath, "r");
    readSync(fd, buf, 0, FOOTER_SIZE, size - FOOTER_SIZE);
    closeSync(fd);

    return buf.subarray(8, 24).toString("utf8") === MAGIC;
  } catch {
    return false;
  }
}

/** 读取归档索引。 */
export function readArchiveIndex(exePath) {
  const size = statSync(exePath).size;
  const footer = Buffer.alloc(FOOTER_SIZE);
  let fd = openSync(exePath, "r");
  readSync(fd, footer, 0, FOOTER_SIZE, size - FOOTER_SIZE);
  closeSync(fd);

  const indexLen = Number(footer.readBigUInt64LE(0));
  if (!Number.isFinite(indexLen) || indexLen <= 0 || indexLen > size) {
    throw new Error("资源索引损坏");
  }

  const indexBuf = Buffer.alloc(indexLen);
  fd = openSync(exePath, "r");
  readSync(fd, indexBuf, 0, indexLen, size - FOOTER_SIZE - indexLen);
  closeSync(fd);

  return JSON.parse(indexBuf.toString("utf8"));
}

/**
 * 把某个归档释放到目标目录。
 *
 * 分块拷贝，避免大文件占满内存。
 */
export function extractArchive(exePath, entry, destDir, onProgress) {
  mkdirSync(destDir, { recursive: true });

  const tarPath = join(destDir, `.${entry.name}.tar`);

  // 分块从 exe 里拷出 tar
  const chunkSize = 8 * 1024 * 1024;
  const chunk = Buffer.alloc(chunkSize);
  const fdRead = openSync(exePath, "r");
  const fdWrite = openSync(tarPath, "w");

  let copied = 0;
  while (copied < entry.size) {
    const want = Math.min(chunkSize, entry.size - copied);
    const got = readSync(fdRead, chunk, 0, want, entry.offset + copied);
    if (got <= 0) break;
    writeSync(fdWrite, chunk, 0, got);
    copied += got;
    onProgress?.(copied, entry.size);
  }
  closeSync(fdRead);
  closeSync(fdWrite);

  // 用系统 tar 解包
  execFileSync("tar", ["-xf", tarPath, "-C", destDir], {
    stdio: "inherit",
    timeout: 30 * 60 * 1000,
  });

  // 删掉临时 tar（用 fs API，比调系统命令可靠）
  try {
    unlinkSync(tarPath);
  } catch {
    /* 删除失败不影响使用 */
  }

  return destDir;
}

/**
 * 释放全部资源到构建目录。
 *
 * @param {string} exePath 当前 exe 路径
 * @param {string} buildRoot 目标构建目录
 * @param {function} log 日志回调
 */
export function extractAll(exePath, buildRoot, log = console.log) {
  if (!hasBundledResources(exePath)) {
    throw new Error("当前 exe 不包含构建环境资源");
  }

  const index = readArchiveIndex(exePath);
  mkdirSync(buildRoot, { recursive: true });

  const map = {
    jdk: "jdk",
    gradle: "gradle-8.7",
    sdk: "sdk",
    workspace: "workspace",
    "gradle-home": "gradle-home",
  };

  for (const entry of index.entries) {
    const target = join(buildRoot, map[entry.name] || entry.name);
    log(`释放 ${entry.name} → ${target}`);
    extractArchive(exePath, entry, target, (done, total) => {
      if (total > 0 && done % (64 * 1024 * 1024) === 0) {
        log(`  ${(done / 1048576).toFixed(0)}/${(total / 1048576).toFixed(0)}MB`);
      }
    });
  }

  return index.entries.length;
}
