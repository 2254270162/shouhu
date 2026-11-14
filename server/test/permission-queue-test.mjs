// 权限请求队列 + 息屏相关的完整流程测试
const BASE = process.env.BASE || "http://127.0.0.1:8096";
const KEY = "perm-queue-test-key-123456";
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
    /* non-json */
  }
  return { status: r.status, json, text };
}

async function dev(path, token) {
  const r = await fetch(BASE + path, { headers: { "X-Device-Token": token } });
  const text = await r.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    /* ignore */
  }
  return { status: r.status, json };
}

console.log("\n[准备]");
{
  const s = await call("/api/admin/setup", { method: "POST", body: { username: "admin", password: "PermTest@2026" }, withCookie: false });
  ok("管理员就绪", s.status === 200, `status=${s.status}`);

  const codeRes = await fetch(BASE + "/api/pairing/code", {
    method: "POST",
    headers: { Authorization: `Bearer ${KEY}`, "Content-Type": "application/json" },
    body: "{}",
  });
  const code = (await codeRes.json()).code;
  const claim = await fetch(BASE + "/api/pairing/claim", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code, deviceName: "权限队列测试机" }),
  });
  const cj = await claim.json();
  globalThis.DEV = cj.deviceId;
  globalThis.TOK = cj.deviceToken;
  ok("设备已配对", !!cj.deviceId);
}

const DEV = globalThis.DEV;
const TOK = globalThis.TOK;

console.log("\n[1] 权限清单接口");
{
  const r = await call("/api/admin/permissions/catalog");
  ok("可读", r.status === 200);
  const perms = r.json?.permissions || [];
  ok("含 5 项", perms.length === 5, `got ${perms.length}`);
  ok("含定位", perms.some((p) => p.key === "location"));
  ok("含使用情况访问", perms.some((p) => p.key === "usage_access" && p.special));
}

console.log("\n[2] 登记权限请求（勾选指定项）");
{
  const r = await call(`/api/admin/devices/${DEV}/request-permissions`, {
    method: "POST",
    body: { keys: ["camera", "notification"] },
  });
  ok("登记成功", r.status === 200 && r.json?.ok === true, JSON.stringify(r.json));
  ok("返回排队状态", r.json?.queued === true);
  ok("提示不会打扰", (r.json?.message || "").includes("打开 App"), r.json?.message);
}

console.log("\n[3] 设备拉取待办（第一次）");
{
  const r = await dev(`/api/devices/${DEV}/pending-permission`, TOK);
  ok("拉到待办", r.status === 200 && r.json?.pending != null, JSON.stringify(r.json));
  ok("权限项正确", JSON.stringify(r.json?.pending?.keys) === '["camera","notification"]', JSON.stringify(r.json?.pending?.keys));
}

console.log("\n[4] 再次拉取应为空（已消费）");
{
  const r = await dev(`/api/devices/${DEV}/pending-permission`, TOK);
  ok("已取走", r.json?.pending === null, JSON.stringify(r.json));
}

console.log("\n[5] 非法权限 key 被拒");
{
  const r = await call(`/api/admin/devices/${DEV}/request-permissions`, {
    method: "POST",
    body: { keys: ["camera", "hacker_permission"] },
  });
  ok("被拒 400", r.status === 400, `got ${r.status}`);
  ok("指出哪个不认识", (r.json?.message || "").includes("hacker_permission"), r.json?.message);
}

console.log("\n[6] 空 keys 表示全部缺失项");
{
  const r = await call(`/api/admin/devices/${DEV}/request-permissions`, { method: "POST", body: { keys: [] } });
  ok("空数组合法", r.status === 200, `got ${r.status}`);
  const p = await dev(`/api/devices/${DEV}/pending-permission`, TOK);
  ok("拉到空列表", Array.isArray(p.json?.pending?.keys) && p.json.pending.keys.length === 0);
}

console.log("\n[7] 未登录不能登记");
{
  const r = await fetch(BASE + `/api/admin/devices/${DEV}/request-permissions`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ keys: ["camera"] }),
  });
  ok("被拒 401", r.status === 401, `got ${r.status}`);
}

console.log("\n[8] 设备上线不再自动发通知指令");
{
  // 登记一个请求，然后模拟设备上线，应该只收到 pending 信号，不是 command
  await call(`/api/admin/devices/${DEV}/request-permissions`, { method: "POST", body: { keys: ["camera"] } });

  const { WebSocket } = await import("ws");
  const ws = new WebSocket(BASE.replace("http", "ws") + "/ws");
  const messages = [];
  await new Promise((resolve, reject) => {
    ws.on("open", () => ws.send(JSON.stringify({ type: "hello", deviceId: DEV, token: TOK, name: "测试" })));
    ws.on("message", (raw) => {
      const m = JSON.parse(raw.toString());
      messages.push(m.type);
      if (m.type === "hello_ack") setTimeout(resolve, 1500);
    });
    ws.on("error", reject);
    setTimeout(resolve, 3000);
  });
  ws.close();

  ok("收到 hello_ack", messages.includes("hello_ack"));
  ok("收到 pending 信号", messages.includes("pending_permission_request"), messages.join(","));
  ok("没有下发 command", !messages.includes("command"), messages.join(","));
}

console.log(`\n${failures === 0 ? "✅ 全部通过" : `❌ ${failures} 项失败`}\n`);
process.exit(failures === 0 ? 0 : 1);
