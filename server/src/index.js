import { createApp } from "./http.js";
import { buildRoutes } from "./routes.js";
import { buildAdminRoutes } from "./adminRoutes.js";
import { adminCount } from "./adminAuth.js";
import { attachWebSocket } from "./ws.js";
import { db } from "./db.js";
import { localAddresses, printStartupBanner } from "./netinfo.js";
import { existsSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";

const PORT = Number(process.env.PORT || 8080);
const HOST = process.env.HOST || "0.0.0.0";

/**
 * 打包成 exe 后的数据目录。
 *
 * 情况一：正常 node 运行 → 用当前工作目录下的 data/
 * 情况二：pkg 打包成 exe → process.execPath 是 exe 路径，
 *         数据要放在 exe 旁边（而不是解压的临时快照目录里），
 *         否则每次启动数据都会丢。
 */
function resolveDataDir() {
  if (process.env.DATA_DIR) return process.env.DATA_DIR;

  const isPacked = typeof process.pkg !== "undefined";
  if (isPacked) {
    return join(dirname(process.execPath), "guardian-data");
  }
  return join(process.cwd(), "data");
}

const DATA_DIR = resolveDataDir();
process.env.DATA_DIR = DATA_DIR;
process.env.PHOTO_DIR = process.env.PHOTO_DIR || join(DATA_DIR, "photos");

try {
  mkdirSync(DATA_DIR, { recursive: true });
} catch (e) {
  console.error(`无法创建数据目录 ${DATA_DIR}:`, e.message);
  process.exit(1);
}

const app = createApp({ ...buildRoutes(), ...buildAdminRoutes() });
const wss = attachWebSocket(app);

app.on("error", (e) => {
  if (e.code === "EADDRINUSE") {
    console.error("");
    console.error(`  端口 ${PORT} 已被占用。`);
    console.error(`  可能是已经有一个服务端在运行，或者换一个端口：`);
    console.error(`  设置环境变量 PORT=8081 后重新启动。`);
    console.error("");
    process.exit(1);
  }
  console.error("服务启动失败:", e.message);
  process.exit(1);
});

app.listen(PORT, HOST, () => {
  console.log(`[guardian] 数据目录 ${DATA_DIR}`);
  console.log(`[guardian] 已配对设备 ${Object.keys(db().devices).length} 台`);
  if (adminCount() === 0) {
    console.log(`[guardian] 尚未创建管理员，首次打开网页时会引导创建`);
  }
  printStartupBanner(PORT, localAddresses());
});

// 优雅退出：先断开长连接再关服务器，避免设备端看到半关闭连接
for (const sig of ["SIGINT", "SIGTERM"]) {
  process.on(sig, () => {
    console.log(`\n[guardian] 收到 ${sig}，正在关闭…`);
    for (const ws of wss.clients) {
      try {
        ws.close(1001, "server shutting down");
      } catch {
        /* 忽略 */
      }
    }
    app.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 3000);
  });
}
