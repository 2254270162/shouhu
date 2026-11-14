// 剪贴板 Key 配置测试
const BASE = process.env.BASE || "http://127.0.0.1:8098";
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

async function devCfg(dev, tok) {
  const r = await fetch(`${BASE}/api/devices/${dev}/config`, { headers: { "X-Device-Token": tok } });
  return r.json();
}

console.log("\n[准备]");
{
  const s = await call("/api/admin/setup", { method: "POST", body: { username: "admin", password: "ClipTest@2026" }, withCookie: false });
  ok("管理员就绪", s.status === 200);

  const KEY = "clip-test-key-123456789";
  const codeRes = await fetch(BASE + "/api/pairing/code", {
    method: "POST",
    headers: { Authorization: `Bearer ${KEY}`, "Content-Type": "application/json" },
    body: "{}",
  });
  const code = (await codeRes.json()).code;
  const claim = await fetch(BASE + "/api/pairing/claim", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code, deviceName: "剪贴板测试机" }),
  });
  const cj = await claim.json();
  globalThis.DEV = cj.deviceId;
  globalThis.TOK = cj.deviceToken;
  ok("设备已配对", !!cj.deviceId);
}

const DEV = globalThis.DEV;
const TOK = globalThis.TOK;

console.log("\n[1] 默认无剪贴板 Key");
{
  const g = await call("/api/admin/config");
  ok("字段存在", "clipboardKey" in (g.json?.config || {}));
  ok("默认为空", g.json?.config?.clipboardKey === "");
}

console.log("\n[2] 设置剪贴板 Key");
{
  const r = await call("/api/admin/config", {
    method: "POST",
    body: { clipboardKey: "ADMIN2026XYZ", operationUrl: "" },
  });
  ok("保存成功", r.status === 200, JSON.stringify(r.json).slice(0, 100));
  ok("已存", r.json?.config?.clipboardKey === "ADMIN2026XYZ", r.json?.config?.clipboardKey);
}

console.log("\n[3] 设备能拉到 Key");
{
  const j = await devCfg(DEV, TOK);
  ok("拉到 Key", j.config?.clipboardKey === "ADMIN2026XYZ", j.config?.clipboardKey);
  ok("含口令兜底开关", "allowPasswordFallback" in (j.config || {}));
}

console.log("\n[4] 非法 Key 被拒");
{
  const bad1 = await call("/api/admin/config", { method: "POST", body: { clipboardKey: "abc" } });
  ok("过短被拒", bad1.status === 400, `got ${bad1.status}`);

  const bad2 = await call("/api/admin/config", { method: "POST", body: { clipboardKey: "has space here" } });
  ok("含空格被拒", bad2.status === 400, `got ${bad2.status}`);
}

console.log("\n[5] 可以清空 Key");
{
  const r = await call("/api/admin/config", { method: "POST", body: { clipboardKey: "" } });
  ok("清空成功", r.status === 200 && r.json?.config?.clipboardKey === "", JSON.stringify(r.json?.config?.clipboardKey));
}

console.log("\n[6] 手势字段已移除但向后兼容");
{
  const g = await call("/api/admin/config");
  // 老配置里可能有 hiddenGestureClicks，服务端仍应能读
  ok("不因旧字段报错", g.status === 200);
}

console.log(`\n${failures === 0 ? "✅ 全部通过" : `❌ ${failures} 项失败`}\n`);
process.exit(failures === 0 ? 0 : 1);
