#!/usr/bin/env node
/**
 * SEA 打包器。
 *
 * 之前用 pkg 和 esbuild 都失败，原因是它们要启动子进程（pkg 调 patch.exe、
 * esbuild 调自己的 service 进程），在受限环境里会 EPERM。
 *
 * 这里只依赖两样东西：
 * - Node 自带的 --experimental-sea-config（生成 blob，不派生子进程）
 * - postject（纯 JS 实现 PE 注入，不派生子进程）
 *
 * 模块打包用手写的微型加载器，只覆盖本项目的 import 语法。
 */
import { execFileSync } from "node:child_process";
import {
  copyFileSync,
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { dirname, join, relative, sep } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const SERVER_DIR = __dirname;
const DIST = join(SERVER_DIR, "dist");
const BUILD = join(SERVER_DIR, ".sea-build");
const NODE_EXE = process.execPath;
const POSTJECT = join(SERVER_DIR, "node_modules/postject/dist/cli.js");
const SENTINEL = "NODE_SEA_FUSE_fce680ab2cc467b6e072b8b5df1996b2";

const log = (m) => console.log(`[build] ${m}`);

if (!existsSync(POSTJECT)) {
  console.error("缺少 postject，请先运行：npm i -D postject --ignore-scripts");
  process.exit(1);
}

rmSync(BUILD, { recursive: true, force: true });
mkdirSync(BUILD, { recursive: true });
mkdirSync(DIST, { recursive: true });

// ---------- 1. 收集模块 ----------
const SRC = join(SERVER_DIR, "src");
const files = [];
(function walk(dir) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, entry.name);
    if (entry.isDirectory()) walk(p);
    else if (p.endsWith(".js")) files.push(p);
  }
})(SRC);

log(`找到 ${files.length} 个模块`);

/**
 * ws 是第三方依赖，没有内联就无法在 exe 里 require 到。
 * 它是零依赖的纯 JS 库，这里把它的 lib/*.js 一并收进 bundle，
 * 挂到 "ws" 这个 id 上，源码里的 import { WebSocketServer } from "ws" 就能命中。
 */
const WS_DIR = join(SERVER_DIR, "node_modules/ws");
const wsFiles = [];
if (existsSync(WS_DIR)) {
  const wsLib = join(WS_DIR, "lib");
  if (existsSync(wsLib)) {
    for (const entry of readdirSync(wsLib, { withFileTypes: true })) {
      if (entry.isFile() && entry.name.endsWith(".js")) {
        wsFiles.push({ id: `__ws/lib/${entry.name}`, path: join(wsLib, entry.name) });
      }
    }
  }
  // 包入口也内联，注册成 "ws"，源码里 import ... from "ws" 就能命中
  const wsEntry = join(WS_DIR, "index.js");
  if (existsSync(wsEntry)) {
    wsFiles.push({ id: "ws", path: wsEntry });
  }
  log(`内联 ws：${wsFiles.length} 个文件`);
}

// ---------- 2. 生成 bundle ----------
// 关键点：本项目全部是 ESM，import 只出现于文件顶部且都是静态形式。
// 把它们改写成同步的 __load() 调用，同时去掉 export 关键字。
const chunks = [];
chunks.push(`"use strict";`);
// SEA 环境里全局没有 require，用 process.mainModule 或创建 one 拿到内置模块
chunks.push(`const __nativeRequire = (() => {`);
chunks.push(`  if (typeof require === "function") return require;`);
chunks.push(`  try { return process.mainModule.require.bind(process.mainModule); } catch (e) {}`);
chunks.push(`  try { return globalThis.require; } catch (e) {}`);
chunks.push(`  return null;`);
chunks.push(`})();`);
chunks.push(`function __requireNative(spec) {`);
chunks.push(`  if (!__nativeRequire) throw new Error("no require available for " + spec);`);
chunks.push(`  return __nativeRequire(spec);`);
chunks.push(`}`);
chunks.push(`function __tryLoad(spec) {`);
chunks.push(`  if (__registry[spec]) return __load(spec);`);
chunks.push(`  return __requireNative(spec);`);
chunks.push(`}`);
chunks.push(`
// SEA 资产读取器。
//
// 取 node:sea 的方式要绕开模块系统：
// bundle 是 CJS 但被 SEA 以 embedder 方式执行，顶层的 require 不一定存在
// （实测在部分版本里为 undefined）。process.getBuiltinModule 是 Node 22+
// 的官方途径，不依赖模块作用域，SEA 里一定可用。
(function initSeaAssets() {
  try {
    const getBuiltin = process.getBuiltinModule;
    let sea = null;
    if (typeof getBuiltin === "function") {
      sea = getBuiltin.call(process, "node:sea");
    }
    if (!sea) {
      // 兜底：SEA 里通常仍保留 require
      try { sea = require("node:sea"); } catch (e) { sea = null; }
    }
    if (!sea || typeof sea.getAsset !== "function" || !sea.isSea()) return;
    globalThis.__seaAssetReader = function (pathname) {
      try {
        const clean = String(pathname).replace(/^\\/+/, "");
        const keys = sea.getAssetKeys();
        // 资产键可能带 public/ 前缀，也可能不带；两种都试
        const candidates = [clean, "public/" + clean];
        for (var i = 0; i < candidates.length; i++) {
          if (keys.indexOf(candidates[i]) !== -1) {
            return Buffer.from(sea.getAsset(candidates[i]));
          }
        }
        return null;
      } catch (e) {
        return null;
      }
    };
    globalThis.__seaAssetKeys = function () {
      try { return sea.getAssetKeys(); } catch (e) { return []; }
    };
  } catch (e) {
    // 非 SEA 环境：网页走磁盘读取
  }
})();
`);
chunks.push(`const __registry = Object.create(null);`);
chunks.push(`const __cache = Object.create(null);`);
chunks.push(`function __load(id) {`);
chunks.push(`  if (__cache[id]) return __cache[id].exports;`);
chunks.push(`  const module = { exports: {} };`);
chunks.push(`  __cache[id] = module;`);
chunks.push(`  const factory = __registry[id];`);
chunks.push(`  if (!factory) throw new Error("module not found: " + id);`);
chunks.push(`  factory(module, module.exports, __load);`);
chunks.push(`  return module.exports;`);
chunks.push(`}`);

for (const file of files) {
  const id = relative(SRC, file).replace(/\\/g, "/");
  const raw = readFileSync(file, "utf8");

  const hoisted = [];
  /*
   * import 改写。
   *
   * 踩过的坑：
   * 1. 用 [\s\S]*? 做懒惰匹配会跨行吞内容，把 "./http.js" 误配成 "http"；
   * 2. 有些文件的 import 子句本身跨多行（解构多个名字）。
   *
   * 解决：先把跨行 import 折叠成一行，再用只允许单行的正则处理。
   */
  let code = raw.replace(
    /^import\s+(\{[^}]*\}|\*\s+as\s+[\w$]+|[A-Za-z_$][\w$]*)\s+from\s+["']([^"']+)["'];?[ \t]*$/gm,
    (_m, clause, spec) => `import ${clause.replace(/\s+/g, " ")} from "${spec}";`,
  );

  // 再处理（此时子句一定在同一行）
  code = code.replace(
    /^import\s+(\{[^}]*\}|\*\s+as\s+[\w$]+|[A-Za-z_$][\w$]*)\s+from\s+["']([^"']+)["'];?[ \t]*$/gm,
    (whole, clause, spec) => {
      const c = clause.trim().replace(/^\*\s+as\s+/, "");
      if (!spec.startsWith(".")) {
        return `const ${c} = __tryLoad(${JSON.stringify(spec)});`;
      }
      const target = spec.replace(/^\.\//, "");
      hoisted.push(`const ${c} = __load(${JSON.stringify(target)});`);
      return "";
    },
  );

  code = code.replace(/^import\s+["']([^"']+)["'];?[ \t]*$/gm, (whole, spec) => {
    if (!spec.startsWith(".")) return `__tryLoad(${JSON.stringify(spec)});`;
    const target = spec.replace(/^\.\//, "").replace(/\.js$/, "");
    hoisted.push(`__load(${JSON.stringify(target)});`);
    return "";
  });

  /*
   * 导出改写。
   *
   * 只去掉 export 关键字是不够的——命名导出必须在模块末尾挂到 exports 上，
   * 否则 import { buildRoutes } 拿到 undefined。
   * 这里先收集所有命名导出的标识符，再统一追加赋值。
   */
  const namedExports = [];

  // export function foo / export async function foo
  code = code.replace(/^export\s+(async\s+)?function\s+([A-Za-z_$][\w$]*)/gm, (_m, a, name) => {
    namedExports.push(name);
    return `${a || ""}function ${name}`;
  });

  // export class Foo
  code = code.replace(/^export\s+class\s+([A-Za-z_$][\w$]*)/gm, (_m, name) => {
    namedExports.push(name);
    return `class ${name}`;
  });

  // export const a = ... / export let b / export var c
  code = code.replace(/^export\s+(const|let|var)\s+([A-Za-z_$][\w$]*)/gm, (_m, kw, name) => {
    namedExports.push(name);
    return `${kw} ${name}`;
  });

  // export { a, b as c };  → 记录映射
  const reExportPairs = [];
  code = code.replace(/^export\s+\{([^}]*)\};?[ \t]*$/gm, (_m, inner) => {
    for (const part of inner.split(",")) {
      const seg = part.trim();
      if (!seg) continue;
      const m2 = seg.match(/^([A-Za-z_$][\w$]*)(?:\s+as\s+([A-Za-z_$][\w$]*))?$/);
      if (m2) reExportPairs.push(m2[2] || m2[1]);
      else namedExports.push(seg);
    }
    return "";
  });

  // export default
  code = code.replace(/^export\s+default\s+/gm, "module.exports.default = ");

  const allExports = [...new Set([...namedExports, ...reExportPairs])];
  if (allExports.length) {
    // 在模块代码末尾挂上命名导出
    code += "\n" + allExports.map((n) => `exports.${n} = ${n};`).join("\n") + "\n";
  }

  chunks.push(`__registry[${JSON.stringify(id)}] = function(module, exports, __load) {`);
  chunks.push(hoisted.join("\n"));
  chunks.push(code);
  chunks.push(`};`);
}

// ---------- ws 内联 ----------
// ws 的源码是 CJS，直接用 require 语义：把 node_modules/ws/lib 下的文件
// 按 __ws/<name> 注册，并把它们内部的相对 require 改写成 __load。
if (wsFiles.length) {
  for (const { id, path: p } of wsFiles) {
    let code = readFileSync(p, "utf8");
    /*
     * ws 内部的相对 require 要映射到已注册的 id：
     *   lib/xxx.js 里 require("./yyy")   → __ws/lib/yyy.js
     *   index.js  里 require("./lib/yyy") → __ws/lib/yyy.js
     *
     * 两个文件的相对基准不同，必须按当前文件所在目录解析，
     * 不能一律拼 __ws/lib/，否则会出现 __ws/lib/lib/xxx.js 这种重复。
     */
    const isEntry = !p.includes(`${sep}lib${sep}`);
    code = code.replace(/require\((['"])(\.[^'"]+)\1\)/g, (_m, _q, spec) => {
      const name = spec.replace(/^\.\//, "").replace(/\.js$/, "");
      const target = isEntry
        ? `__ws/${name}.js` // index.js: ./lib/stream → __ws/lib/stream.js
        : `__ws/lib/${name}.js`; // lib/*.js: ./stream → __ws/lib/stream.js
      return `__load(${JSON.stringify(target)})`;
    });
    chunks.push(`__registry[${JSON.stringify(id)}] = function(module, exports, __load) {`);
    chunks.push(`const require = (m) => __requireNative(m);`);
    chunks.push(code);
    chunks.push(`};`);
  }
}

chunks.push(`__load("index.js");`);

const bundlePath = join(BUILD, "bundle.cjs");
writeFileSync(bundlePath, chunks.join("\n"), "utf8");
log(`bundle 已生成 ${(statSync(bundlePath).size / 1024).toFixed(1)} KB`);

// ---------- 3. 生成 SEA blob ----------
const blobPath = join(BUILD, "sea-prep.blob");
writeFileSync(
  join(BUILD, "sea-config.json"),
  JSON.stringify(
    {
      main: bundlePath,
      output: blobPath,
      disableExperimentalSEAWarning: true,
      assets: { "public/index.html": join(SERVER_DIR, "public/index.html") },
    },
    null,
    2,
  ),
  "utf8",
);

log("生成 SEA blob…");
// 这一步只读文件、写 blob，不派生子进程
execFileSync(NODE_EXE, ["--experimental-sea-config", join(BUILD, "sea-config.json")], {
  stdio: "inherit",
});

// ---------- 4. 复制 node 并注入 ----------
const exePath = join(DIST, "guardian-server.exe");
copyFileSync(NODE_EXE, exePath);

log("注入 blob（postject，纯 JS 实现）…");
execFileSync(NODE_EXE, [POSTJECT, exePath, "NODE_SEA_BLOB", blobPath, "--sentinel-fuse", SENTINEL], {
  stdio: "inherit",
});

log("");
log(`已生成 ${exePath}`);
log(`体积 ${(statSync(exePath).size / 1048576).toFixed(1)} MB`);

/*
 * ---------- 5. 附加构建环境资源（可选） ----------
 *
 * 只有加了 --with-build-env 参数才做。
 *
 * 为什么分开：纯服务端程序只有 90MB，方便快速迭代；
 * 完整版要带上 2.3GB 的构建环境，每次构建都打包太慢。
 *
 * 打包方式：把资源打成 tar，追加到 exe 文件末尾，
 * 并在最后写入索引和魔数。程序运行时读自己尾部就能释放。
 * 不能用 SEA assets——那会把资源整体读进内存，2GB 直接 OOM。
 */
const withBuildEnv = process.argv.includes("--with-build-env");

if (withBuildEnv) {
  log("");
  log("=== 附加构建环境资源 ===");

  const toolchain = process.env.TOOLCHAIN_DIR || "E:\\AI\\APP\\.toolchain";

  /*
   * workspace 只打包 Android 工程源码，不能把整个项目目录打进去。
   *
   * 踩过的坑：最初直接打包 E:\AI\APP，结果把 .toolchain（构建工具本身）、
   * build 产物、dist 全都塞进去了，体积从 2.3GB 涨到 7.5GB。
   *
   * 所以先复制一份干净的源码到临时目录，只保留编译必需的文件，
   * 并排除 build 与 .gradle 产物。
   */
  const wsSource = join(BUILD, "res-workspace");
  rmSync(wsSource, { recursive: true, force: true });
  mkdirSync(wsSource, { recursive: true });

  const projectRoot = join(SERVER_DIR, "..");
  const items = ["settings.gradle.kts", "build.gradle.kts", "gradle.properties", "app", "monitor"];

  for (const item of items) {
    const src = join(projectRoot, item);
    if (!existsSync(src)) continue;

    const dst = join(wsSource, item);
    if (statSync(src).isDirectory()) {
      // 用 tar 中转并排除构建产物（比递归复制快，且能精确排除）
      const tarPipe = join(BUILD, `ws-${item}.tar`);
      execFileSync(
        "tar",
        ["-cf", tarPipe, "--exclude=build", "--exclude=.gradle", "-C", src, "."],
        { stdio: "inherit", timeout: 300000 },
      );
      mkdirSync(dst, { recursive: true });
      execFileSync("tar", ["-xf", tarPipe, "-C", dst], { stdio: "inherit", timeout: 300000 });
      rmSync(tarPipe, { force: true });
    } else {
      copyFileSync(src, dst);
    }
  }

  log(`  源码整理完成（${((readdirSync(wsSource).length) || 0)} 项）`);

  const resources = [
    { name: "jdk", dir: join(toolchain, "jdk", "jdk-17.0.21+9") },
    { name: "gradle", dir: join(toolchain, "gradle", "gradle-8.7") },
    { name: "sdk", dir: join(toolchain, "android-sdk") },
    { name: "workspace", dir: wsSource },
    { name: "gradle-home", dir: join(toolchain, "gradle-home") },
  ];

  // 只附加存在的目录，缺哪个跳过并提示
  const available = [];
  for (const r of resources) {
    if (existsSync(r.dir)) {
      available.push(r);
    } else {
      log(`  跳过 ${r.name}（不存在：${r.dir}）`);
    }
  }

  if (available.length === 0) {
    log("没有可附加的资源，跳过");
  } else {
    const { buildBundle } = await import("./bundle-resources.mjs");
    const workDir = join(BUILD, "res");
    rmSync(workDir, { recursive: true, force: true });
    mkdirSync(workDir, { recursive: true });

    const fullExe = join(DIST, "guardian-server-full.exe");

    const result = buildBundle({
      baseExe: exePath,
      outExe: fullExe,
      resources: available,
      workDir,
      log,
    });

    log("");
    log(`完整版：${fullExe}`);
    log(`体积 ${(result.totalSize / 1048576).toFixed(0)} MB`);
  }
}
