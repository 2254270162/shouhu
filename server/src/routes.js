import { db, persist } from "./db.js";
import {
  hashKey,
  pairingCode,
  randomToken,
  safeEqual,
  extractKey,
  extractDeviceToken,
  validateKeyStrength,
} from "./auth.js";
import { sendCommand, isOnline } from "./ws.js";
import { savePhoto, readPhoto } from "./storage.js";
import { readBinaryBody, assetDebugInfo } from "./http.js";
import { sniffPhoto } from "./imageType.js";
import { logEvent, readLogs, clearLogs, LogLevel } from "./deviceLog.js";
import { effectiveConfig } from "./deviceConfig.js";
import { takePermissionRequest } from "./permissionRequest.js";

const PAIR_TTL_MS = 10 * 60 * 1000; // 配对码 10 分钟有效
const MAX_PHOTO_BYTES = 20 * 1024 * 1024;

/** 抛出一个带 HTTP 状态码的错误，由 http.js 统一转成响应。 */
function fail(status, error, message) {
  const err = new Error(message || error);
  err.status = status;
  err.code = error;
  throw err;
}

/**
 * 校验监控端 Key。
 *
 * 注册规则：
 * - 服务端开启「首次使用自动注册」时（默认开启，单人自用场景），任何合法强度的
 *   Key 第一次出现即成为该 Key 的账号；
 * - 但注册只发生在「生成配对码」这一个动作上，其余接口要求 Key 已注册。
 *   这样随便拿个 Key 调 /api/devices 不会悄悄建号，也便于排查输错 Key。
 */
function requireKey(ctx, { allowRegister = false } = {}) {
  const key = extractKey(ctx.req, ctx.url);
  if (!key) fail(401, "missing_key", "缺少监控端 Key");

  const bad = validateKeyStrength(key);
  if (bad) fail(400, "weak_key", bad);

  const hashed = hashKey(key);
  const monitors = db().monitors;
  let existing = Object.entries(monitors).find(([h]) => safeEqual(h, hashed))?.[1];

  if (!existing) {
    if (!allowRegister) {
      fail(401, "key_not_registered", "这个 Key 还没注册过，请在监控端点「连接服务器」完成初始化");
    }
    monitors[hashed] = { hash: hashed, createdAt: Date.now(), lastUsedAt: Date.now() };
    persist();
    existing = monitors[hashed];
  } else {
    existing.lastUsedAt = Date.now();
  }

  const owned = Object.values(db().devices).filter(
    (d) => d.ownerKeyHash && safeEqual(d.ownerKeyHash, hashed),
  );
  return { key, hashed, owned };
}

/** 校验设备令牌。 */
function requireDevice(ctx) {
  const token = extractDeviceToken(ctx.req, ctx.url);
  const deviceId = ctx.params.deviceId || ctx.body?.deviceId;
  const device = db().devices[deviceId];
  if (!device) fail(404, "device_not_found", "设备不存在");
  if (!token || !safeEqual(device.token, token)) fail(401, "bad_device_token", "设备令牌无效");
  return device;
}

/** 监控端访问某台设备前的归属校验，避免越权读别人的设备。 */
function requireOwnedDevice(ctx) {
  const { hashed, owned } = requireKey(ctx);
  const device = owned.find((d) => d.id === ctx.params.deviceId);
  if (!device) fail(404, "device_not_found", "设备不存在或不属于该 Key");
  return { device, hashed };
}

function publicDevice(d) {
  return {
    id: d.id,
    name: d.name,
    model: d.model || null,
    appVersion: d.appVersion || null,
    online: isOnline(d.id),
    createdAt: d.createdAt,
    lastSeenAt: d.lastSeenAt || null,
    lastLocation: d.lastLocation || null,
    photoCount: d.photos?.length || 0,
  };
}

export function buildRoutes() {
  const routes = {
    /** 健康检查：监控端配置服务器地址时用来探测是否连通 */
    "GET /api/health": () => ({
      body: {
        ok: true,
        service: "guardian",
        version: "1.0.0",
        serverTime: Date.now(),
        deviceCount: Object.keys(db().devices).length,
      },
    }),

    /** 诊断：确认打包形态与网页资源是否可用（排查 404 用） */
    "GET /api/diag": () => ({
      body: {
        ok: true,
        asset: assetDebugInfo(),
        node: process.version,
        cwd: process.cwd(),
        execPath: process.execPath,
      },
    }),

    /**
     * 监控端初始化：第一次用某个 Key 时调用。
     * 现在 Key 由 requireKey 自动注册，这里只做连通性与强度校验。
     */
    "POST /api/monitor/init": (ctx) => {
      const key = extractKey(ctx.req, ctx.url) || ctx.body?.key;
      if (!key) fail(401, "missing_key", "缺少监控端 Key");
      const bad = validateKeyStrength(key);
      if (bad) fail(400, "weak_key", bad);
      ctx.req.headers["authorization"] = `Bearer ${key}`;
      const { hashed, owned } = requireKey(ctx, { allowRegister: true });
      return {
        body: {
          ok: true,
          keyHashPrefix: hashed.slice(0, 12),
          deviceCount: owned.length,
        },
      };
    },

    /** 监控端生成配对码：App 内直接展示，供被监控端输入 */
    "POST /api/pairing/code": (ctx) => {
      // 这是唯一允许"首次出现即注册"的动作
      const { hashed } = requireKey(ctx, { allowRegister: true });
      const code = pairingCode();
      db().pairingCodes[code] = {
        code,
        ownerKeyHash: hashed,
        createdAt: Date.now(),
        expiresAt: Date.now() + PAIR_TTL_MS,
      };
      persist();
      return {
        body: {
          ok: true,
          code,
          expiresAt: Date.now() + PAIR_TTL_MS,
          // 被监控端需要填写的服务器地址由服务端告知，避免手抄错
          serverHint: ctx.body?.serverHint || null,
        },
      };
    },

    /**
     * 设备用「组密钥」自助注册。
     *
     * 为什么需要：几十台 PDA 一台台填配对码不现实。
     * 编译时把服务器地址和组密钥写进 APK，设备首次启动就能自动注册，
     * 每台仍然拿到**独立**的设备令牌，不是共用凭证。
     *
     * 与配对码的区别：
     * - 配对码：一次性，一码一设备，适合偶尔新增；
     * - 组密钥：可重复使用，适合批量部署，但可随时在后台吊销。
     *
     * 安全约束：
     * - 组密钥必须显式启用才生效（默认关闭），避免误开；
     * - 可设置最大注册数量，防止密钥泄露后被无限注册；
     * - 同一设备（按安装 UUID）重复注册时复用原令牌，不会产生重复设备。
     */
    "POST /api/enroll": (ctx) => {
      const groupKey = String(ctx.body?.groupKey || "").trim();
      if (!groupKey) {
        return { status: 400, body: { ok: false, error: "missing_group_key", message: "缺少组密钥" } };
      }

      const settings = db().settings?.enrollment || {};
      if (!settings.enabled || !settings.groupKey) {
        return {
          status: 403,
          body: { ok: false, error: "enrollment_disabled", message: "服务端未启用预置注册，请用配对码" },
        };
      }
      if (!safeEqual(groupKey, settings.groupKey)) {
        logEvent("enroll", "enroll_failed", "组密钥错误", { ip: ctx.req.socket?.remoteAddress });
        return { status: 401, body: { ok: false, error: "bad_group_key", message: "组密钥不正确" } };
      }

      // 名额检查
      const maxDevices = Number(settings.maxDevices || 0);
      const currentCount = Object.keys(db().devices).length;
      if (maxDevices > 0 && currentCount >= maxDevices) {
        return {
          status: 403,
          body: { ok: false, error: "quota_exceeded", message: `已达注册上限 ${maxDevices} 台` },
        };
      }

      const installId = String(ctx.body?.installId || "").trim();
      const deviceName = String(ctx.body?.deviceName || "未命名设备").slice(0, 60);

      // 同一台设备重复注册：复用已有记录，避免产生重复设备
      if (installId) {
        const existing = Object.values(db().devices).find((d) => d.installId === installId);
        if (existing) {
          existing.lastSeenAt = Date.now();
          existing.name = deviceName || existing.name;
          persist();
          logEvent(existing.id, "enroll", "设备重新注册（复用原令牌）", { installId });
          return {
            body: {
              ok: true,
              deviceId: existing.id,
              deviceToken: existing.token,
              deviceName: existing.name,
              reused: true,
            },
          };
        }
      }

      /*
       * 第二层去重：按「机型 + 设备名」兜底。
       *
       * 为什么需要：installId 现在用 Android ID 生成，正常情况下重装不会变。
       * 但有些 ROM 的 Android ID 会在重装后变化，或者恢复出厂设置，
       * 那时服务端就会多出一台重复设备。
       *
       * 这种情况按机型+名称匹配旧记录并复用，避免设备列表越装越乱。
       * 只匹配「最近 7 天内活跃」的，避免误吞很久以前的同名设备。
       */
      const model = String(ctx.body?.model || "").trim();
      if (model && deviceName) {
        const weekAgo = Date.now() - 7 * 24 * 60 * 60 * 1000;
        const similar = Object.values(db().devices).find(
          (d) =>
            d.model === model &&
            d.name === deviceName &&
            (d.lastSeenAt || 0) > weekAgo,
        );
        if (similar) {
          similar.installId = installId || similar.installId;
          similar.lastSeenAt = Date.now();
          persist();
          logEvent(similar.id, "enroll", "按机型+名称匹配到已有设备，复用原记录", {
            installId,
            model,
          });
          return {
            body: {
              ok: true,
              deviceId: similar.id,
              deviceToken: similar.token,
              deviceName: similar.name,
              reused: true,
            },
          };
        }
      }

      const id = `dev_${randomToken(9)}`;
      const token = randomToken(32);
      // 预置注册可以指定分组：设备装完自动归组，不用后台手动分配
      const presetGroupId = settings.groupId && db().groups?.[settings.groupId]
        ? settings.groupId
        : null;

      db().devices[id] = {
        id,
        name: deviceName,
        model: ctx.body?.model || null,
        appVersion: ctx.body?.appVersion || null,
        installId: installId || null,
        groupId: presetGroupId,
        ownerKeyHash: null, // 组密钥注册的设备不属于某个监控端 Key
        token,
        createdAt: Date.now(),
        lastSeenAt: Date.now(),
        online: false,
        lastLocation: null,
        photos: [],
        settings: { notificationEnabled: true },
      };
      persist();
      logEvent(id, "enroll", "设备通过组密钥注册", {
        groupKeyPrefix: groupKey.slice(0, 4) + "***",
        installId: installId || null,
        groupId: presetGroupId,
      });

      return {
        body: {
          ok: true,
          deviceId: id,
          deviceToken: token,
          deviceName,
          reused: false,
        },
      };
    },

    /**
     * 被监控端用配对码换取设备令牌。
     * 这是唯一不需要监控端 Key 的写接口，因为设备端没有 Key。
     */
    "POST /api/pairing/claim": (ctx) => {
      const code = String(ctx.body?.code || "").trim();
      const deviceName = String(ctx.body?.deviceName || "未命名设备").slice(0, 60);
      const entry = db().pairingCodes[code];
      if (!entry) {
        return { status: 404, body: { ok: false, error: "code_invalid", message: "配对码不正确" } };
      }
      if (Date.now() > entry.expiresAt) {
        delete db().pairingCodes[code];
        persist();
        return { status: 410, body: { ok: false, error: "code_expired", message: "配对码已过期，请在监控端重新生成" } };
      }

      const id = `dev_${randomToken(9)}`;
      const token = randomToken(32);
      db().devices[id] = {
        id,
        name: deviceName,
        model: ctx.body?.model || null,
        appVersion: ctx.body?.appVersion || null,
        ownerKeyHash: entry.ownerKeyHash,
        token,
        createdAt: Date.now(),
        lastSeenAt: Date.now(),
        online: false,
        lastLocation: null,
        photos: [],
        settings: {
          // 被监控端始终显示常驻通知；这里记录用户的选择，便于审计
          notificationEnabled: true,
        },
      };
      // 一次性使用，兑换后立即销毁
      delete db().pairingCodes[code];
      persist();
      logEvent(id, "pair", "设备完成配对", { name: deviceName, model: ctx.body?.model || null });
      return { body: { ok: true, deviceId: id, deviceToken: token, deviceName } };
    },

    /** 监控端拉取自己名下所有设备 */
    "GET /api/devices": (ctx) => {
      const { owned } = requireKey(ctx);
      return { body: { ok: true, devices: owned.map(publicDevice) } };
    },

    /** 单台设备详情 */
    "GET /api/devices/:deviceId": (ctx) => {
      const { device } = requireOwnedDevice(ctx);
      return { body: { ok: true, device: publicDevice(device) } };
    },

    /** 监控端解绑设备：同时把令牌作废，被监控端会立刻断开 */
    "DELETE /api/devices/:deviceId": (ctx) => {
      const { device } = requireOwnedDevice(ctx);
      delete db().devices[device.id];
      persist();
      return { body: { ok: true, removed: device.id } };
    },

    /**
     * 设备自助解绑。
     *
     * 这是"用户随时可以撤销"的关键一环：被监控端不需要监控端配合，
     * 凭自己的令牌就能把自己从服务端移除，监控端随即看不到这台设备。
     */
    "POST /api/devices/:deviceId/self": (ctx) => {
      const device = requireDevice(ctx);
      logEvent(device.id, "unpair", "设备自助解绑");
      delete db().devices[device.id];
      clearLogs(device.id);
      persist();
      return { body: { ok: true, removed: device.id } };
    },

    /**
     * 设备拉取待处理的权限请求。
     *
     * 设备进入前台时调用一次：如果有待办就返回，取走后清掉。
     * 这是「远程请求权限」真正落地的环节——用户在 App 里才会弹系统权限框。
     */
    "GET /api/devices/:deviceId/pending-permission": (ctx) => {
      const device = requireDevice(ctx);
      const req = takePermissionRequest(device.id);
      if (!req) return { body: { ok: true, pending: null } };
      return {
        body: {
          ok: true,
          pending: {
            keys: req.keys || [],
            requestedAt: req.requestedAt,
          },
        },
      };
    },

    /** 设备端上报位置（HTTP 兜底，WS 断开时也能上传） */
    "POST /api/devices/:deviceId/location": (ctx) => {
      const device = requireDevice(ctx);
      const loc = ctx.body?.location;
      if (!loc || typeof loc.latitude !== "number" || typeof loc.longitude !== "number") {
        return { status: 400, body: { ok: false, error: "invalid_location" } };
      }
      device.lastLocation = { ...loc, updatedAt: Date.now() };
      device.lastSeenAt = Date.now();
      persist();
      logEvent(device.id, "location", "上报位置", {
        latitude: loc.latitude,
        longitude: loc.longitude,
        accuracy: loc.accuracy,
        provider: loc.provider,
      });
      broadcastToMonitors(device.id, { type: "location", deviceId: device.id, location: device.lastLocation });
      return { body: { ok: true } };
    },

    /** 设备端上报运行状态（HTTP 兜底） */
    "POST /api/devices/:deviceId/telemetry": (ctx) => {
      const device = requireDevice(ctx);
      const t = ctx.body?.telemetry;
      if (!t || typeof t !== "object") {
        return { status: 400, body: { ok: false, error: "invalid_telemetry" } };
      }
      device.lastTelemetry = { ...t, updatedAt: Date.now() };
      device.lastSeenAt = Date.now();

      // 权限状态单独存一份，便于列表页直接展示缺哪些
      const perms = t.permissions;
      if (perms && Array.isArray(perms.items)) {
        device.permissions = {
          updatedAt: Date.now(),
          items: perms.items,
          missingCount: perms.missingCount || 0,
          missingLabels: perms.missingLabels || "",
          complete: perms.complete !== false,
        };
      }

      persist();
      logEvent(device.id, "telemetry", "上报运行状态", summarizeForLog(t));
      broadcastToMonitors(device.id, { type: "telemetry", deviceId: device.id, telemetry: device.lastTelemetry });

      // 回传当前生效的运行配置，设备据此调整后台模式
      return { body: { ok: true, config: effectiveConfig(device) } };
    },

    /**
     * 设备拉取自己的运行配置。
     *
     * 用途：服务端可以按设备或全局指定「后台模式」（是否使用前台服务/常驻通知）。
     * 这样管理员在后台改一次，所有 PDA 下次上线自动生效，不用重装 APK。
     */
    "GET /api/devices/:deviceId/config": (ctx) => {
      const device = requireDevice(ctx);
      return { body: { ok: true, config: effectiveConfig(device) } };
    },

    /** 监控端主动拉取一次设备运行状态 */
    "POST /api/devices/:deviceId/telemetry/refresh": async (ctx) => {
      const { device } = requireOwnedDevice(ctx);
      try {
        const result = await sendCommand(device.id, { command: "telemetry" }, 30_000);
        if (result.ok && result.telemetry) {
          device.lastTelemetry = { ...result.telemetry, updatedAt: Date.now() };
          persist();
        }
        return { body: { ok: !!result.ok, telemetry: device.lastTelemetry, error: result.error || null } };
      } catch (e) {
        return {
          status: 200,
          body: { ok: false, error: e.code || "unavailable", message: e.message, telemetry: device.lastTelemetry || null },
        };
      }
    },

    /** 监控端读取某台设备的日志 */
    "GET /api/devices/:deviceId/logs": (ctx) => {
      const { device } = requireOwnedDevice(ctx);
      const limit = Math.min(Number(ctx.url.searchParams.get("limit") || 200), 1000);
      const since = Number(ctx.url.searchParams.get("since") || 0);
      const event = ctx.url.searchParams.get("event");
      return {
        body: {
          ok: true,
          logs: readLogs(device.id, { limit, since, event }),
        },
      };
    },

    /** 监控端主动要求设备立刻定位一次 */
    "POST /api/devices/:deviceId/locate": async (ctx) => {
      const { device } = requireOwnedDevice(ctx);
      try {
        const result = await sendCommand(device.id, { command: "locate" }, 60_000);
        if (result.ok && result.location) {
          device.lastLocation = { ...result.location, updatedAt: Date.now() };
          persist();
        }
        return { body: { ok: !!result.ok, location: device.lastLocation, error: result.error || null } };
      } catch (e) {
        // 设备离线/超时对监控端是"暂时拿不到"，返回 200 + ok:false 更好处理
        return {
          status: 200,
          body: { ok: false, error: e.code || "unavailable", message: e.message, location: device.lastLocation },
        };
      }
    },

    /** 监控端请求设备拍一张照片 */
    "POST /api/devices/:deviceId/capture": async (ctx) => {
      const { device } = requireOwnedDevice(ctx);
      const lens = ctx.body?.lens === "front" ? "front" : "back";
      try {
        const result = await sendCommand(device.id, { command: "capture", lens }, 90_000);
        return {
          body: {
            ok: !!result.ok,
            error: result.error || null,
            data: result.data || null,
            // 设备判断画面全黑时带回来，监控端据此提示"镜头可能被遮挡"
            warning: result.warning || null,
          },
        };
      } catch (e) {
        return { status: 200, body: { ok: false, error: e.code || "unavailable", message: e.message } };
      }
    },

    /** 设备端上传一张照片（二进制 body + JSON 元数据在 query 里） */
    "POST /api/devices/:deviceId/photos": async (ctx) => {
      const device = requireDevice(ctx);
      const buf = await readBinaryBody(ctx.req, MAX_PHOTO_BYTES);
      if (buf.length === 0) return { status: 400, body: { ok: false, error: "empty_body" } };

      const photoId = String(ctx.url.searchParams.get("photoId") || `p_${randomToken(8)}`).replace(/[^\w-]/g, "");
      const source = ctx.url.searchParams.get("source") === "camera" ? "camera" : "gallery";
      const takenAt = Number(ctx.url.searchParams.get("takenAt") || Date.now());
      const lat = ctx.url.searchParams.get("lat");
      const lon = ctx.url.searchParams.get("lon");

      // 相册里可能是 PNG/WebP/HEIC，不能假定都是 JPEG；
      // 按真实字节判断类型，读取时才能返回正确的 Content-Type。
      const { mime, ext } = sniffPhoto(buf);
      const saved = savePhoto(device.id, photoId, buf, ext);
      writePhotoMeta(device, {
        id: photoId,
        source,
        takenAt,
        createdAt: Date.now(),
        size: saved.size,
        sha256: saved.sha256,
        latitude: lat ? Number(lat) : null,
        longitude: lon ? Number(lon) : null,
        mime,
        ext,
        uploadedBy: "device",
      });
      return { body: { ok: true, photoId } };
    },

    /** 监控端列出某台设备的照片 */
    "GET /api/devices/:deviceId/photos": (ctx) => {
      const { device } = requireOwnedDevice(ctx);
      const photos = [...(device.photos || [])].sort((a, b) => b.createdAt - a.createdAt);
      return { body: { ok: true, photos } };
    },

    /** 监控端下载照片原图 */
    "GET /api/devices/:deviceId/photos/:photoId": (ctx) => {
      const { device } = requireOwnedDevice(ctx);
      const found = readPhoto(device.id, ctx.params.photoId);
      if (!found) fail(404, "photo_not_found", "照片不存在");
      return { raw: found.buf, contentType: found.mime };
    },

    /** 监控端要求设备把相册里最近的照片同步上来 */
    "POST /api/devices/:deviceId/sync-album": async (ctx) => {
      const { device } = requireOwnedDevice(ctx);
      const limit = Math.min(Number(ctx.body?.limit || 20), 100);
      try {
        const result = await sendCommand(device.id, { command: "sync_album", limit }, 120_000);
        return { body: { ok: !!result.ok, error: result.error || null, synced: result.synced || 0 } };
      } catch (e) {
        return { status: 200, body: { ok: false, error: e.code || "unavailable", message: e.message } };
      }
    },

    /** 监控端探测设备链路延迟 */
    "POST /api/devices/:deviceId/ping": async (ctx) => {
      const { device } = requireOwnedDevice(ctx);
      try {
        const result = await sendCommand(device.id, { command: "ping" }, 15_000);
        return { body: { ok: !!result.ok, rtt: result.rtt || null } };
      } catch (e) {
        return { status: 200, body: { ok: false, error: e.code || "unavailable", message: e.message } };
      }
    },
  };
  return routes;
}

function writePhotoMeta(device, meta) {
  device.photos = device.photos || [];
  device.photos = device.photos.filter((p) => p.id !== meta.id); // 重传时覆盖
  device.photos.push(meta);
  // 只保留最近 500 张的索引，磁盘文件由清理脚本处理
  if (device.photos.length > 500) {
    device.photos = device.photos.slice(-500);
  }
  persist();
  logEvent(
    device.id,
    "photo",
    meta.source === "camera" ? "上传拍照" : "上传相册照片",
    { photoId: meta.id, size: meta.size, mime: meta.mime },
  );
}

/** 遥测对象 → 可读摘要，存进日志便于回溯。 */
function summarizeForLog(t) {
  if (!t) return null;
  const parts = [];
  if (t.battery?.level != null) parts.push(`电量 ${t.battery.level}%`);
  if (t.battery?.temperatureC != null) parts.push(`${Number(t.battery.temperatureC).toFixed(1)}°C`);
  if (t.battery?.charging) parts.push(t.battery.charging === "full" ? "已充满" : "充电中");
  if (t.screen?.on != null) parts.push(t.screen.on ? "亮屏" : "息屏");
  if (t.foregroundApp?.packageName) parts.push(`前台 ${t.foregroundApp.packageName}`);
  if (t.memory?.usedPercent != null) parts.push(`内存 ${t.memory.usedPercent}%`);
  if (t.storage?.usedPercent != null) parts.push(`存储 ${t.storage.usedPercent}%`);
  return { summary: parts.join(" · "), raw: t };
}

/** 服务端向所有监控端连接推送（简单广播，监控端按 deviceId 过滤） */
const monitorSockets = new Set();
export function registerMonitorSocket(ws) {
  monitorSockets.add(ws);
  ws.on("close", () => monitorSockets.delete(ws));
}
function broadcastToMonitors(_deviceId, payload) {
  const text = JSON.stringify(payload);
  for (const ws of monitorSockets) {
    if (ws.readyState === ws.OPEN) {
      try {
        ws.send(text);
      } catch {
        /* 忽略单条失败 */
      }
    }
  }
}
