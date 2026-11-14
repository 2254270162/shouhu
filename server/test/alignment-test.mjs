// 四端对齐验证：服务端 + exe 接口全覆盖
const BASE = process.env.BASE || "http://127.0.0.1:8099";
const KEY = "align-test-key-123456789";
let cookie = "";
let failures = 0;
const ok = (n, c, e = "") => {
  console.log(`  ${c ? "✅" : "❌"} ${n}${c ? "" : " " + e}`);
  if (!c) failures++;
};

async function call(path, { method = "GET", body, withCookie = true } = {}) {
  const headers = {};
  if (body) headers["Content-Type"] = "application/json";
  if (withCookie && cookie) headers["Cookie"] = cookie;
  const r = await fetch(BASE + path, { method, headers, body: body ? JSON.stringify(body) : undefined });
  const sc = r.headers.get("set-cookie");
  if (sc) cookie = sc.split(";")[0];
  const text = await r.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    /* ignore */
  }
  return { status: r.status, json, text };
}

async function dev(path, token, method = "GET", body = null) {
  const headers = { "X-Device-Token": token };
  if (body) headers["Content-Type"] = "application/json";
  const r = await fetch(BASE + path, { method, headers, body: body ? JSON.stringify(body) : undefined });
  const text = await r.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    /* ignore */
  }
  return { status: r.status, json };
}

console.log("=== 四端对齐验证 ===\n");

console.log("[准备] 管理员 + 设备");
{
  const s = await call("/api/admin/setup", { method: "POST", body: { username: "admin", password: "AlignTest@2026" }, withCookie: false });
  ok("管理员就绪", s.status === 200);

  const codeRes = await fetch(BASE + "/api/pairing/code", {
    method: "POST",
    headers: { Authorization: `Bearer ${KEY}`, "Content-Type": "application/json" },
    body: "{}",
  });
  const code = (await codeRes.json()).code;
  const claim = await fetch(BASE + "/api/pairing/claim", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code, deviceName: "对齐测试机" }),
  });
  const cj = await claim.json();
  globalThis.DEV = cj.deviceId;
  globalThis.TOK = cj.deviceToken;
  ok("设备已配对", !!cj.deviceId);
}

const DEV = globalThis.DEV;
const TOK = globalThis.TOK;

console.log("\n[1] 设备端能力（被监控端必须支持）");
{
  // 遥测（含剪贴板、权限）
  const t = await dev(`/api/devices/${DEV}/telemetry`, TOK, "POST", {
    telemetry: {
      battery: { level: 80, temperatureC: 30 },
      screen: { on: true },
      memory: { usedPercent: 50 },
      storage: { usedPercent: 40 },
      permissions: { items: [{ key: "location", label: "定位", granted: true }], missingCount: 0, complete: true },
      clipboard: { text: "测试剪贴板", length: 5, fromHistory: false, recent: ["测试剪贴板"] },
    },
  });
  ok("遥测上报（含剪贴板）", t.status === 200, `got ${t.status}`);

  // 配置
  const c = await dev(`/api/devices/${DEV}/config`, TOK);
  ok("拉取配置", c.status === 200 && c.json?.ok === true);
  ok("含运行模式", "backgroundMode" in (c.json?.config || {}));
  ok("含操作地址", "operationUrl" in (c.json?.config || {}));
  ok("含剪贴板Key", "clipboardKey" in (c.json?.config || {}));

  // 待办权限
  const pp = await dev(`/api/devices/${DEV}/pending-permission`, TOK);
  ok("待办权限接口", pp.status === 200);

  // 位置
  const loc = await dev(`/api/devices/${DEV}/location`, TOK, "POST", {
    location: { latitude: 30.5, longitude: 114.3, accuracy: 10 },
  });
  ok("位置上报", loc.status === 200);
}

console.log("\n[2] 监控端能力（网页 + App 都需要）");
{
  const list = await call("/api/admin/devices");
  ok("设备列表", list.status === 200);
  const dev = (list.json?.devices || [])[0];
  ok("含遥测数据", dev?.lastTelemetry != null);
  ok("含权限状态", dev?.permissions != null);

  const detail = await call(`/api/admin/devices/${DEV}`);
  ok("设备详情", detail.status === 200);

  const cfg = await call("/api/admin/config");
  ok("读配置", cfg.status === 200);
  ok("配置含品牌相关", "operationUrl" in (cfg.json?.config || {}));

  const catalog = await call("/api/admin/permissions/catalog");
  ok("权限清单", catalog.status === 200 && (catalog.json?.permissions || []).length > 0);

  const logs = await call(`/api/admin/devices/${DEV}/logs`);
  ok("日志", logs.status === 200);
}

console.log("\n[3] 剪贴板（两端都要能读）");
{
  // 监控端读
  const monRead = await call(`/api/admin/devices/${DEV}/clipboard`, { method: "POST", body: {} });
  ok("监控端读剪贴板接口存在", monRead.status === 200, `got ${monRead.status}`);
  // 设备离线时应该明确失败而不是 500
  ok("离线时明确返回", monRead.json?.ok === false || monRead.json?.ok === true);
}

console.log("\n[4] 品牌定制");
{
  const b = await call("/api/admin/branding");
  ok("品牌信息", b.status === 200);
  ok("含默认名称", !!b.json?.defaults?.appName);

  const build = await call("/api/admin/build/env");
  ok("构建环境", build.status === 200);
}

console.log("\n[5] 管理端功能");
{
  const apks = await call("/api/admin/apks");
  ok("安装包列表", apks.status === 200);

  const batch = await call("/api/admin/pairing/batch", { method: "POST", body: { count: 3 } });
  ok("批量配对码", batch.status === 200 && (batch.json?.codes || []).length === 3);

  const req = await call(`/api/admin/devices/${DEV}/request-permissions`, {
    method: "POST",
    body: { keys: ["camera"] },
  });
  ok("请求权限", req.status === 200);
}

console.log("\n[6] 鉴权一致性");
{
  const noAuth = await fetch(BASE + "/api/admin/devices");
  ok("无 Cookie 被拒", noAuth.status === 401);

  const badDevice = await dev(`/api/devices/${DEV}/telemetry`, "wrong", "POST", { telemetry: {} });
  ok("错误设备令牌被拒", badDevice.status === 401);
}

console.log(`\n${failures === 0 ? "✅ 全部通过" : `❌ ${failures} 项失败`}\n`);
process.exit(failures === 0 ? 0 : 1);
