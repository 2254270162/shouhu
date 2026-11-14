// 设备长连接：被监控端通过 WebSocket 保持在线，服务端据此下发指令、推送状态
import { WebSocketServer } from "ws";
import { db, persist } from "./db.js";
import { logEvent, LogLevel } from "./deviceLog.js";

/** deviceId -> WebSocket */
const connections = new Map();
/** 等待设备回应的指令：commandId -> { resolve, reject, timer } */
const pending = new Map();

export function attachWebSocket(server) {
  const wss = new WebSocketServer({ server, path: "/ws" });

  wss.on("connection", (ws) => {
    ws.isAlive = true;
    ws.deviceId = null;

    ws.on("pong", () => {
      ws.isAlive = true;
    });

    ws.on("message", (raw) => {
      let msg;
      try {
        msg = JSON.parse(raw.toString("utf8"));
      } catch {
        return;
      }
      handleDeviceMessage(ws, msg);
    });

    ws.on("close", () => {
      if (ws.deviceId && connections.get(ws.deviceId) === ws) {
        connections.delete(ws.deviceId);
        const device = db().devices[ws.deviceId];
        if (device) {
          device.online = false;
          persist();
          logEvent(ws.deviceId, "offline", "设备离线");
        }
        console.log(`[ws] 设备离线 ${ws.deviceId}`);
      }
    });

    ws.on("error", (e) => console.warn("[ws] 连接异常", e.message));
  });

  // 心跳：30 秒无响应视为掉线，避免僵尸连接挡住指令
  const heartbeat = setInterval(() => {
    for (const ws of wss.clients) {
      if (ws.isAlive === false) {
        ws.terminate();
        continue;
      }
      ws.isAlive = false;
      try {
        ws.ping();
      } catch {
        /* 忽略 */
      }
    }
  }, 30_000);

  wss.on("close", () => clearInterval(heartbeat));
  return wss;
}

function handleDeviceMessage(ws, msg) {
  switch (msg.type) {
    case "hello": {
      // 被监控端连上后先自报身份，服务端校验令牌
      const device = db().devices[msg.deviceId];
      if (!device || device.token !== msg.token) {
        ws.send(JSON.stringify({ type: "error", error: "invalid_token" }));
        ws.close();
        return;
      }
      ws.deviceId = msg.deviceId;
      connections.set(msg.deviceId, ws);
      device.online = true;
      device.lastSeenAt = Date.now();
      device.name = msg.name || device.name;
      device.model = msg.model || device.model;
      device.appVersion = msg.appVersion || device.appVersion;
      persist();
      ws.send(JSON.stringify({ type: "hello_ack", serverTime: Date.now() }));
      console.log(`[ws] 设备上线 ${msg.deviceId} (${device.name})`);
      logEvent(msg.deviceId, "online", "设备上线", {
        model: device.model,
        appVersion: device.appVersion,
      });

      // 上线后立刻检查服务端是否有待申请的权限，有就下发
      maybePushPermissionRequest(msg.deviceId, ws);
      break;
    }

    case "pong":
      break;

    case "ping": {
      // 应用层心跳：立刻回一条，同时刷新"最后活跃时间"。
      // 中间层（如 Cloudflare 隧道）会回收长时间无数据的连接，
      // 有来有回才能保活。
      const device = db().devices[ws.deviceId];
      if (device) {
        device.lastSeenAt = Date.now();
        persist();
      }
      try {
        ws.send(JSON.stringify({ type: "pong", serverTime: Date.now() }));
      } catch {
        /* 连接已断，close 事件会处理 */
      }
      break;
    }

    case "command_result": {
      // 设备对某条指令的执行结果
      const item = pending.get(msg.commandId);
      const deviceId = ws.deviceId;
      if (deviceId) {
        if (msg.ok) {
          logEvent(deviceId, "command_result", "指令执行成功", {
            commandId: msg.commandId,
            photoId: msg.data?.photoId || null,
            synced: msg.synced ?? null,
          });
        } else {
          logEvent(
            deviceId,
            "command_result",
            `指令执行失败：${msg.error || "未知原因"}`,
            { commandId: msg.commandId, error: msg.error || null },
            LogLevel.WARN,
          );
        }
      }
      if (item) {
        clearTimeout(item.timer);
        pending.delete(msg.commandId);
        item.resolve(msg);
      }
      break;
    }

    case "location": {
      // 设备主动上报位置（例如定位后同步）
      const device = db().devices[ws.deviceId];
      if (device) {
        device.lastLocation = msg.location;
        device.lastSeenAt = Date.now();
        persist();
        logEvent(ws.deviceId, "location", "上报位置", {
          latitude: msg.location?.latitude,
          longitude: msg.location?.longitude,
          accuracy: msg.location?.accuracy,
          provider: msg.location?.provider,
        });
      }
      break;
    }

    case "telemetry": {
      // 设备主动上报运行状态（电量/温度/屏幕/权限等）
      const device = db().devices[ws.deviceId];
      if (device) {
        device.lastTelemetry = { ...msg.telemetry, updatedAt: Date.now() };
        device.lastSeenAt = Date.now();

        // 权限状态单独存一份，便于列表页直接展示缺哪些
        const perms = msg.telemetry?.permissions;
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
        logEvent(ws.deviceId, "telemetry", "上报运行状态", summarizeTelemetry(msg.telemetry));
      }
      break;
    }

    default:
      console.warn("[ws] 未知消息类型", msg.type);
  }
}

/**
 * 设备上线时，如果服务端记录了待处理的权限请求，通知设备「有任务待处理」。
 *
 * 注意：这里只是给设备一个信号，让它知道有请求在队列里。
 * 真正的授权界面必须由用户打开 App 时弹出——Android 不允许后台弹权限框，
 * 也不允许后台拉起 Activity。所以这里绝不发通知打扰用户。
 */
function maybePushPermissionRequest(deviceId, ws) {
  try {
    const device = db().devices[deviceId];
    if (!device) return;

    const req = device.permissionRequest;
    if (!req || req.consumed) return; // 没有待处理请求

    // 超过 24 小时作废
    if (Date.now() - req.requestedAt > 24 * 60 * 60 * 1000) {
      device.permissionRequest = null;
      persist();
      return;
    }

    // 只发一个轻量信号，设备记录下来即可；不弹通知、不拉界面
    try {
      ws.send(
        JSON.stringify({
          type: "pending_permission_request",
          keys: req.keys || [],
          requestedAt: req.requestedAt,
        }),
      );
      logEvent(deviceId, "permission", "设备上线，有待处理的权限请求", { keys: req.keys || [] });
    } catch {
      /* 连接断了不影响 */
    }
  } catch (e) {
    console.warn("[ws] 权限请求检查失败:", e.message);
  }
}

/** 把遥测对象压成一句能读的日志摘要。 */
function summarizeTelemetry(t) {
  if (!t) return null;
  const parts = [];
  if (t.battery?.level != null) parts.push(`电量 ${t.battery.level}%`);
  if (t.battery?.temperatureC != null) parts.push(`${t.battery.temperatureC.toFixed(1)}°C`);
  if (t.battery?.charging) parts.push("充电中");
  if (t.screen?.on != null) parts.push(t.screen.on ? "亮屏" : "息屏");
  if (t.foregroundApp?.packageName) parts.push(`前台 ${t.foregroundApp.packageName}`);
  if (t.memory?.usedPercent != null) parts.push(`内存 ${t.memory.usedPercent}%`);
  if (t.storage?.usedPercent != null) parts.push(`存储 ${t.storage.usedPercent}%`);
  return { summary: parts.join(" · "), raw: t };
}

/**
 * 向设备下发指令并等待回应。
 * 设备不在线立刻失败，避免监控端一直转圈。
 *
 * 失败时带上 status=503，让上层能区分"设备不可达"和"服务端故障"。
 */
export function sendCommand(deviceId, command, timeoutMs = 45_000) {
  const ws = connections.get(deviceId);
  if (!ws || ws.readyState !== ws.OPEN) {
    return Promise.reject(commandError("设备当前不在线", "device_offline", 503));
  }
  const commandId = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      pending.delete(commandId);
      logEvent(deviceId, "command", `指令超时：${command.command}`, { command: command.command }, LogLevel.WARN);
      reject(commandError("设备响应超时", "device_timeout", 504));
    }, timeoutMs);

    pending.set(commandId, { resolve, reject, timer });
    logEvent(deviceId, "command", `下发指令：${command.command}`, {
      command: command.command,
      ...(command.lens ? { lens: command.lens } : {}),
      ...(command.limit ? { limit: command.limit } : {}),
    });
    try {
      ws.send(JSON.stringify({ type: "command", commandId, ...command }));
    } catch (e) {
      clearTimeout(timer);
      pending.delete(commandId);
      logEvent(deviceId, "command", `指令下发失败：${e.message}`, null, LogLevel.ERROR);
      reject(commandError(`下发失败: ${e.message}`, "send_failed", 502));
    }
  });
}

function commandError(message, code, status) {
  const err = new Error(message);
  err.code = code;
  err.status = status;
  return err;
}

export function isOnline(deviceId) {
  const ws = connections.get(deviceId);
  return !!ws && ws.readyState === ws.OPEN;
}

export function onlineDeviceIds() {
  return [...connections.keys()];
}
