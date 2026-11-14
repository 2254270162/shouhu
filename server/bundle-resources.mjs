import { existsSync, openSync, closeSync, readSync, statSync, unlinkSync, writeSync } from "node:fs";
import { join } from "node:path";
import { execFileSync } from "node:child_process";

/**
 * 把构建环境打包成 exe 尾部的附加归档。
 *
 * 为什么不用 Node SEA 的 assets：
 * SEA 资源会被整体读进内存，2.3GB 的体积直接 OOM。
 *
 * 方案：在 exe 文件末尾追加一个归档区，格式是
 *   [原 exe][归档数据][目录索引][索引长度(8字节)][魔数(16字节)]
 * exe 启动时读自己最后 24 字节拿到索引位置，就能按需解出单个归档。
 *
 * 这样 exe 仍是单个文件，双击即用，运行时才释放资源。
 */

export const ARCHIVE_MAGIC = "GUARDIANRES00001"; // 16 字节
export const FOOTER_SIZE = 24; // 8 字节索引长度 + 16 字节魔数

/** 目录索引格式：JSON，包含每个归档的名字、偏移、长度。 */
function buildIndex(entries) {
  return Buffer.from(JSON.stringify({ version: 1, entries }), "utf8");
}

/**
 * 把目录打包成一个 tar 文件（用系统 tar，避免自己实现压缩）。
 * Windows 10+ 自带 bsdtar。
 */
export function packDirectory(srcDir, outFile) {
  if (existsSync(outFile)) unlinkSync(outFile);
  execFileSync("tar", ["-cf", outFile, "-C", srcDir, "."], {
    stdio: "inherit",
    timeout: 30 * 60 * 1000,
  });
  return statSync(outFile).size;
}

/**
 * 把归档追加到 exe 末尾。
 *
 * @param {string} exePath 基础 exe（已注入 SEA blob）
 * @param {Array<{name:string, file:string}>} archives 要附加的归档
 */
export function appendArchives(exePath, archives) {
  const baseSize = statSync(exePath).size;
  const entries = [];
  let offset = baseSize;

  // 同步写入：需要确保持久化完成才能返回，否则调用方读到的可能是半截文件
  const fdOut = openSync(exePath, "a");
  const BUFSIZE = 4 * 1024 * 1024;
  const chunk = Buffer.alloc(BUFSIZE);

  try {
    for (const a of archives) {
      const size = statSync(a.file).size;
      const fdIn = openSync(a.file, "r");
      let pos = 0;
      while (pos < size) {
        const want = Math.min(BUFSIZE, size - pos);
        const got = readSync(fdIn, chunk, 0, want, pos);
        if (got <= 0) break;
        writeSync(fdOut, chunk, 0, got);
        pos += got;
      }
      closeSync(fdIn);

      entries.push({ name: a.name, offset, size });
      offset += size;
    }

    // 追加索引 + footer
    const index = buildIndex(entries);
    writeSync(fdOut, index);

    const footer = Buffer.alloc(FOOTER_SIZE);
    footer.writeBigUInt64LE(BigInt(index.length), 0);
    footer.write(ARCHIVE_MAGIC, 8, "utf8");
    writeSync(fdOut, footer);

    return { entries, totalSize: offset + index.length + FOOTER_SIZE };
  } finally {
    closeSync(fdOut);
  }
}

/**
 * 生成完整的分发包：基础 exe + 构建环境归档。
 *
 * 分两步：
 * 1. 先按原流程生成带 SEA blob 的 exe（约 90MB）；
 * 2. 再把构建环境归档追加到它末尾。
 */
export function buildBundle({ baseExe, outExe, resources, workDir, log = console.log }) {
  log("准备构建环境归档…");

  // 逐个打包成 tar，避免一次性占满内存
  const archives = [];
  for (const r of resources) {
    const tarPath = join(workDir, `${r.name}.tar`);
    log(`  打包 ${r.name}（${r.dir}）…`);
    const size = packDirectory(r.dir, tarPath);
    log(`  ${r.name}: ${(size / 1048576).toFixed(0)}MB`);
    archives.push({ name: r.name, file: tarPath });
  }

  log("复制基础 exe…");
  execFileSync(
    process.platform === "win32" ? "cmd" : "sh",
    process.platform === "win32" ? ["/c", "copy", "/Y", baseExe, outExe] : ["-c", `cp "${baseExe}" "${outExe}"`],
    { stdio: "inherit" },
  );

  log("追加归档到 exe 末尾…");
  const result = appendArchives(outExe, archives);
  log(`完成: ${outExe}`);
  log(`总体积 ${(result.totalSize / 1048576).toFixed(0)}MB`);

  return result;
}
