// 遥测与设备日志的端到端测试
import { WebSocket } from "ws";

const BASE = process.env.BASE || "http://127.0.0.1:8082";
const KEY = "telemetry-test-key-12345";
let failures = 0;

function ok(name, cond, extra = "") {
  console.log(`  ${cond ? "✅" : "❌"} ${name}${cond ? "" : " " + extra}`);
  if (!cond) failures++;
}

let cookie = "";
async function web(path, { method = "GET", body } = {}) {
  const headers = {};
  if (body) headers["Content-Type"] = "application/json";
  if (cookie) headers["Cookie"] = cookie;
  const res = await fetch(BASE + path, { method, headers, body: body ? JSON.stringify(body) : undefined });
  const sc = res.headers.get("set-cookie");
  if (sc) cookie = sc.split(";")[0];
  const text = await res.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    /* 非 JSON */
  }
  return { status: res.status, json, text };
}

async function dev(path, { method = "POST", body, token } = {}) {
  const headers = { "X-Device-Token": token };
  if (body) headers["Content-Type"] = "application/json";
  const res = await fetch(BASE + path, { method, headers, body: body ? JSON.stringify(body) : undefined });
  const text = await res.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    /* 非 JSON */
  }
  return { status: res.status, json };
}

console.log("\n[准备] 管理员与设备");
{
  const setup = await web("/api/admin/setup", { method: "POST", body: { username: "admin", password: "Telemetry@2026" } });
  ok("管理员已创建", setup.status === 200, JSON.stringify(setup.json));

  const codeRes = await fetch(BASE + "/api/pairing/code", {
    method: "POST",
    headers: { Authorization: `Bearer ${KEY}`, "Content-Type": "application/json" },
    body: "{}",
  });
  const code = (await codeRes.json()).code;

  const claim = await fetch(BASE + "/api/pairing/claim", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code, deviceName: "遥测测试机", model: "TestPhone", appVersion: "1.0" }),
  });
  const cj = await claim.json();
  globalThis.DEVICE_ID = cj.deviceId;
  globalThis.DEVICE_TOKEN = cj.deviceToken;
  ok("设备已配对", !!cj.deviceId, JSON.stringify(cj));
}

const DEVICE_ID = globalThis.DEVICE_ID;
const DEVICE_TOKEN = globalThis.DEVICE_TOKEN;

console.log("\n[1] 设备上报遥测（HTTP）");
const sampleTelemetry = {
  battery: { available: true, level: 78, temperatureC: 32.5, status: "charging", charging: true, pluggedType: "usb", health: "good" },
  screen: { on: true, powerSaveMode: false },
  memory: { totalBytes: 8589934592, availableBytes: 3006477107, usedBytes: 5583457485, usedPercent: 65, lowMemory: false },
  storage: { totalBytes: 128849018880, availableBytes: 51539607552, usedBytes: 77309411328, usedPercent: 60 },
  foregroundApp: { available: true, packageName: "com.tencent.mm", appLabel: "微信" },
};
{
  const r = await dev(`/api/devices/${DEVICE_ID}/telemetry`, {
    body: { telemetry: sampleTelemetry },
    token: DEVICE_TOKEN,
  });
  ok("上报成功", r.status === 200 && r.json?.ok === true, JSON.stringify(r.json));

  const bad = await dev(`/api/devices/${DEVICE_ID}/telemetry`, { body: { telemetry: "not-an-object" }, token: DEVICE_TOKEN });
  ok("非法遥测被拒 400", bad.status === 400, `got ${bad.status}`);

  const noAuth = await dev(`/api/devices/${DEVICE_ID}/telemetry`, { body: { telemetry: sampleTelemetry }, token: "bad" });
  ok("错误令牌被拒 401", noAuth.status === 401, `got ${noAuth.status}`);
}

console.log("\n[2] 后台能读到遥测");
{
  const r = await web("/api/admin/devices");
  const d = (r.json?.devices || []).find((x) => x.id === DEVICE_ID);
  ok("设备存在", !!d);
  ok("遥测已保存", d?.lastTelemetry != null, JSON.stringify(d?.lastTelemetry || null).slice(0, 100));
  ok("电量正确", d?.lastTelemetry?.battery?.level === 78, String(d?.lastTelemetry?.battery?.level));
  ok("温度正确", d?.lastTelemetry?.battery?.temperatureC === 32.5);
  ok("屏幕状态正确", d?.lastTelemetry?.screen?.on === true);
  ok("内存百分比正确", d?.lastTelemetry?.memory?.usedPercent === 65);
  ok("存储百分比正确", d?.lastTelemetry?.storage?.usedPercent === 60);
  ok("前台应用正确", d?.lastTelemetry?.foregroundApp?.packageName === "com.tencent.mm");
}

console.log("\n[3] 未授权前台应用的状态");
{
  const t2 = {
    ...sampleTelemetry,
    foregroundApp: { available: false, reason: "未授予「使用情况访问权限」" },
  };
  await dev(`/api/devices/${DEVICE_ID}/telemetry`, { body: { telemetry: t2 }, token: DEVICE_TOKEN });
  const r = await web("/api/admin/devices");
  const d = (r.json?.devices || []).find((x) => x.id === DEVICE_ID);
  ok("记录为未授权", d?.lastTelemetry?.foregroundApp?.available === false);
  ok("带出原因", (d?.lastTelemetry?.foregroundApp?.reason || "").includes("使用情况访问权限"), d?.lastTelemetry?.foregroundApp?.reason);
}

console.log("\n[4] 日志已生成");
{
  const r = await web(`/api/admin/devices/${DEVICE_ID}/logs`);
  ok("能读到日志", r.status === 200 && Array.isArray(r.json?.logs), JSON.stringify(r.json).slice(0, 100));
  const logs = r.json?.logs || [];
  ok("日志非空", logs.length > 0, `count=${logs.length}`);
  const events = new Set(logs.map((l) => l.event));
  ok("包含配对日志", events.has("pair"), [...events].join(","));
  ok("包含遥测日志", events.has("telemetry"), [...events].join(","));
  ok("最新在前", logs.length < 2 || logs[0].ts >= logs[1].ts);
  const tele = logs.find((l) => l.event === "telemetry");
  ok("遥测日志有摘要", !!tele?.detail?.summary, JSON.stringify(tele?.detail || null).slice(0, 120));
}

console.log("\n[5] 日志过滤与限制");
{
  const r = await web(`/api/admin/devices/${DEVICE_ID}/logs?event=telemetry`);
  const logs = r.json?.logs || [];
  ok("按事件过滤生效", logs.length > 0 && logs.every((l) => l.event === "telemetry"), `count=${logs.length}`);

  const r2 = await web(`/api/admin/devices/${DEVICE_ID}/logs?limit=1`);
  ok("limit 生效", (r2.json?.logs || []).length === 1, `got ${(r2.json?.logs || []).length}`);
}

console.log("\n[6] WebSocket 遥测与实时指令");
{
  const ws = new WebSocket(`${BASE.replace("http", "ws")}/ws`);
  await new Promise((resolve, reject) => {
    ws.on("open", () =>
      ws.send(JSON.stringify({ type: "hello", deviceId: DEVICE_ID, token: DEVICE_TOKEN, name: "遥测测试机" })));
    ws.on("message", (raw) => {
      const m = JSON.parse(raw.toString());
      if (m.type === "hello_ack") resolve();
      if (m.type === "command" && m.command === "telemetry") {
        ws.send(JSON.stringify({ type: "command_result", commandId: m.commandId, ok: true, telemetry: sampleTelemetry }));
      }
    });
    ws.on("error", reject);
    setTimeout(() => reject(new Error("WS 超时")), 8000);
  });

  // 通过 WS 主动推一条遥测
  ws.send(JSON.stringify({ type: "telemetry", telemetry: { ...sampleTelemetry, battery: { ...sampleTelemetry.battery, level: 42 } } }));
  await new Promise((r) => setTimeout(r, 400));

  const r = await web("/api/admin/devices");
  const d = (r.json?.devices || []).find((x) => x.id === DEVICE_ID);
  ok("WS 推送的遥测已入库", d?.lastTelemetry?.battery?.level === 42, String(d?.lastTelemetry?.battery?.level));

  // 管理端请求刷新遥测
  const refresh = await web(`/api/admin/devices/${DEVICE_ID}/telemetry`, { method: "POST", body: {} });
  ok("刷新遥测成功", refresh.json?.ok === true, JSON.stringify(refresh.json).slice(0, 120));
  ok("回传了遥测数据", refresh.json?.telemetry?.battery?.level === 78, JSON.stringify(refresh.json?.telemetry || null).slice(0, 80));

  // 上线/离线日志
  ws.close();
  await new Promise((r2) => setTimeout(r2, 500));

  const logs = (await web(`/api/admin/devices/${DEVICE_ID}/logs`)).json?.logs || [];
  const evs = logs.map((l) => l.event);
  ok("记录了上线", evs.includes("online"), evs.join(","));
  ok("记录了离线", evs.includes("offline"), evs.join(","));
  ok("记录了指令", evs.includes("command"), evs.join(","));
}

console.log("\n[7] 清空日志");
{
  const r = await web(`/api/admin/devices/${DEVICE_ID}/logs`, { method: "DELETE" });
  ok("清空成功", r.json?.ok === true, JSON.stringify(r.json));
  const after = (await web(`/api/admin/devices/${DEVICE_ID}/logs`)).json?.logs || [];
  // 清空会留一条"管理员清空了日志"
  ok("只剩清空记录", after.length <= 1, `count=${after.length}`);
}

console.log("\n[8] 越权访问");
{
  const r = await fetch(BASE + `/api/admin/devices/${DEVICE_ID}/logs`);
  ok("未登录读日志 401", r.status === 401, `got ${r.status}`);
}

console.log(`\n${failures === 0 ? "✅ 全部通过" : `❌ ${failures} 项失败`}\n`);
process.exit(failures === 0 ? 0 : 1);
