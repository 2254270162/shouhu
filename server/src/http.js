// 极简 HTTP 路由 + JSON 解析，避免引入 Express 等依赖
import { createServer } from "node:http";
import { extname, resolve, sep } from "node:path";
import { readFile, stat } from "node:fs/promises";
import { fileURLToPath } from "node:url";

/**
 * 管理后台静态文件根目录。
 *
 * 两种运行形态：
 * - 源码运行：读 server/public 目录；
 * - 打包成 exe（SEA）：目录不存在，网页从内嵌资产读。
 *
 * 注意：这里不能用 import.meta.url —— 打包后是 CJS 环境，会直接语法报错。
 * 改成基于 process.cwd() 推导，源码运行时从工作目录找 public/。
 */
const STATIC_ROOT = (() => {
  try {
    // 源码运行：server/ 目录下执行，public 就在旁边
    return resolve(process.cwd(), "public");
  } catch {
    return null;
  }
})();

/**
 * 读取内嵌的网页资源。
 *
 * SEA 打包后没有文件系统里的 public/ 目录，网页被存进 exe 的资产表，
 * 必须通过 node:sea 的 getAsset 取。
 * 读取器由打包器在 bundle 顶部注入到 globalThis.__seaAssetReader。
 */
function readEmbeddedAsset(pathname) {
  try {
    const reader = globalThis.__seaAssetReader;
    if (typeof reader !== "function") return null;
    return reader(pathname);
  } catch {
    return null;
  }
}

/** 诊断用：当前进程内嵌资产是否可用。 */
export function assetDebugInfo() {
  return {
    hasReader: typeof globalThis.__seaAssetReader === "function",
    assetKeys: typeof globalThis.__seaAssetKeys === "function" ? globalThis.__seaAssetKeys() : [],
    pkg: typeof process.pkg !== "undefined",
    hasGetBuiltin: typeof process.getBuiltinModule === "function",
    staticRoot: STATIC_ROOT,
    staticRootExists: typeof existsSyncSafe === "function" ? existsSyncSafe(STATIC_ROOT) : null,
  };
}

function existsSyncSafe(p) {
  try {
    return require("node:fs").existsSync(p);
  } catch {
    return null;
  }
}

/**
 * 路由表：{ 'GET /api/foo': handler }
 * handler 签名 (ctx) => { status, body } 或 { status, raw, contentType } 或 { status, stream, ... }
 */
export function createApp(routes) {
  return createServer(async (req, res) => {
    const url = new URL(req.url, `http://${req.headers.host || "localhost"}`);

    // 允许 App 从任意来源调用（原生 App 不受 CORS 限制，这里主要方便网页端调试）
    res.setHeader("Access-Control-Allow-Origin", "*");
    res.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Device-Token");
    res.setHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
    if (req.method === "OPTIONS") {
      res.writeHead(204).end();
      return;
    }

    const path = url.pathname.replace(/\/+$/, "") || "/";
    let handler = routes[`${req.method} ${path}`];

    // 参数化路由：/api/devices/:id/photos/:photoId
    let params = null;
    if (!handler) {
      for (const key of Object.keys(routes)) {
        const [method, pattern] = key.split(" ");
        if (method !== req.method) continue;
        const names = [];
        const regex = new RegExp(
          "^" + pattern.replace(/:[a-zA-Z]+/g, (m) => {
            names.push(m.slice(1));
            return "([^/]+)";
          }) + "$",
        );
        const m = path.match(regex);
        if (m) {
          handler = routes[key];
          params = Object.fromEntries(names.map((n, i) => [n, decodeURIComponent(m[i + 1])]));
          break;
        }
      }
    }

    if (!handler) {
      // 非 /api 路径交给静态文件：管理员网页走这里
      if (!path.startsWith("/api/")) {
        const assetPath = path === "/" ? "/index.html" : path;

        // 优先用内嵌资产（exe 形态）
        const embedded = readEmbeddedAsset(assetPath);
        if (embedded) {
          res.writeHead(200, {
            "Content-Type": guessType(assetPath),
            "Content-Length": embedded.length,
            "Cache-Control": "no-store",
            "X-Content-Type-Options": "nosniff",
          });
          res.end(embedded);
          return;
        }

        // 源码形态：读磁盘
        if (STATIC_ROOT && (await serveStatic(res, STATIC_ROOT, assetPath))) {
          return;
        }
      }
      sendJson(res, 404, { ok: false, error: "not_found", path });
      return;
    }

    const ctx = { req, res, url, params: params || {} };
    try {
      ctx.body = await readJsonBody(req);
    } catch (e) {
      // 二进制上传（照片）不解析 JSON
      if (e.code !== "NOT_JSON") {
        sendJson(res, 400, { ok: false, error: "bad_body", message: e.message });
        return;
      }
      ctx.body = null;
    }

    try {
      const out = await handler(ctx);
      if (!out) {
        sendJson(res, 204, null);
        return;
      }
      if (out.stream) {
        res.writeHead(out.status || 200, out.headers || {});
        out.stream.pipe(res);
        return;
      }
      if (out.raw !== undefined) {
        res.writeHead(out.status || 200, {
          "Content-Type": out.contentType || "application/octet-stream",
          "Content-Length": Buffer.byteLength(out.raw),
          ...(out.headers || {}),
        });
        res.end(out.raw);
        return;
      }
      // 注意：必须把 handler 返回的 headers 透传下去，
      // 否则 Set-Cookie 会被丢掉，登录后前端拿不到会话。
      sendJson(res, out.status || 200, out.body ?? { ok: true }, out.headers);
    } catch (e) {
      // 业务层通过 err.status / err.code 表达可预期的失败（401/404/400…），
      // 不能一律当成 500，否则客户端拿不到可判断的错误码。
      const status = Number(e.status) || 500;
      if (status >= 500) {
        console.error("[app] 处理失败", path, e);
      }
      sendJson(res, status, {
        ok: false,
        error: e.code || (status === 500 ? "internal" : "request_failed"),
        message: e.message,
      });
    }
  });
}

function sendJson(res, status, body, extraHeaders) {
  if (status === 204 || body === null) {
    res.writeHead(204, extraHeaders || {}).end();
    return;
  }
  const text = JSON.stringify(body);
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(text),
    ...(extraHeaders || {}),
  });
  res.end(text);
}

const MAX_JSON = 1024 * 1024; // 1MB，位置与元数据足够；照片走独立的二进制接口

function readJsonBody(req) {
  if (req.method === "GET" || req.method === "DELETE") return Promise.resolve(null);
  const type = req.headers["content-type"] || "";
  if (!type.includes("application/json")) {
    const err = new Error("not json");
    err.code = "NOT_JSON";
    return Promise.reject(err);
  }
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on("data", (c) => {
      size += c.length;
      if (size > MAX_JSON) {
        reject(new Error("请求体过大"));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on("end", () => {
      if (chunks.length === 0) return resolve({});
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString("utf8")));
      } catch (e) {
        reject(new Error("JSON 解析失败"));
      }
    });
    req.on("error", reject);
  });
}

/** 读取任意二进制请求体，用于照片上传。 */
export function readBinaryBody(req, limitBytes) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on("data", (c) => {
      size += c.length;
      if (size > limitBytes) {
        reject(new Error(`文件超过上限 ${(limitBytes / 1048576).toFixed(0)}MB`));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on("end", () => resolve(Buffer.concat(chunks)));
    req.on("error", reject);
  });
}

/**
 * 静态文件服务，只暴露 public 目录内的文件。
 *
 * 安全要点：先 resolve 成绝对路径，再校验结果仍在 rootDir 内。
 * 只做字符串替换（normalize + 去掉 ../）是不够的，
 * 不同平台的路径规范化差异会留下穿越缺口。
 */
export async function serveStatic(res, rootDir, pathname) {
  const root = resolve(rootDir);
  const relative = decodeURIComponentSafe(pathname).replace(/^[/\\]+/, "");
  const filePath = resolve(root, relative);

  // 必须以 root 为前缀，且不能是 root 本身
  if (filePath !== root && !filePath.startsWith(root + sep)) return false;

  try {
    const info = await stat(filePath);
    if (!info.isFile()) return false;
    const data = await readFile(filePath);
    res.writeHead(200, {
      "Content-Type": guessType(filePath),
      "Content-Length": data.length,
      // 管理后台的 HTML 一律不缓存：改了页面后用户刷新必须立刻看到新版，
      // 否则会误以为改动没生效（这个坑踩过）。
      "Cache-Control": "no-store, no-cache, must-revalidate",
      "Pragma": "no-cache",
      "Expires": "0",
      "X-Content-Type-Options": "nosniff",
    });
    res.end(data);
    return true;
  } catch {
    return false;
  }
}

/** decodeURIComponent 遇到非法编码会抛异常，这里降级为原串。 */
function decodeURIComponentSafe(s) {
  try {
    return decodeURIComponent(s);
  } catch {
    return s;
  }
}

function guessType(p) {
  const map = {
    ".html": "text/html; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".webp": "image/webp",
    ".json": "application/json; charset=utf-8",
  };
  return map[extname(p).toLowerCase()] || "application/octet-stream";
}
