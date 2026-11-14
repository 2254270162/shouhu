// 隐藏手势配置测试
const BASE = process.env.BASE || "http://127.0.0.1:8097";
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

console.log("\n[准备]");
{
  const s = await call("/api/admin/setup", { method: "POST", body: { username: "admin", password: "HideTest@2026" }, withCookie: false });
  ok("管理员就绪", s.status === 200);

  const KEY = "hide-test-key-123456789";
  const codeRes = await fetch(BASE + "/api/pairing/code", {
    method: "POST",
    headers: { Authorization: `Bearer ${KEY}`, "Content-Type": "application/json" },
    body: "{}",
  });
  const code = (await codeRes.json()).code;
  const claim = await fetch(BASE + "/api/pairing/claim", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code, deviceName: "隐藏测试机" }),
  });
  const cj = await claim.json();
  globalThis.DEV = cj.deviceId;
  globalThis.TOK = cj.deviceToken;
  ok("设备已配对", !!cj.deviceId);
}

const DEV = globalThis.DEV;
const TOK = globalThis.TOK;

console.log("\n[1] 默认配置");
{
  const g = await call("/api/admin/config");
  ok("有操作地址字段", "operationUrl" in (g.json?.config || {}));
  ok("默认空地址", g.json?.config?.operationUrl === "");
  ok("默认手势 10 次", g.json?.config?.hiddenGestureClicks === 10, String(g.json?.config?.hiddenGestureClicks));
}

console.log("\n[2] 设置操作地址与手势");
{
  const r = await call("/api/admin/config", {
    method: "POST",
    body: { operationUrl: "https://wms.example.com", hiddenGestureClicks: 12, adminPassword: "test1234" },
  });
  ok("保存成功", r.status === 200, JSON.stringify(r.json).slice(0, 100));
  ok("地址已存", r.json?.config?.operationUrl === "https://wms.example.com");
  ok("手势已存", r.json?.config?.hiddenGestureClicks === 12);
  ok("口令已存", r.json?.config?.adminPassword === "test1234");
}

console.log("\n[3] 设备能拉到这些配置");
{
  const r = await fetch(`${BASE}/api/devices/${DEV}/config`, { headers: { "X-Device-Token": TOK } });
  const j = await r.json();
  ok("拉到操作地址", j.config?.operationUrl === "https://wms.example.com");
  ok("拉到手势次数", j.config?.hiddenGestureClicks === 12);
  ok("拉倒口令", j.config?.adminPassword === "test1234");
}

console.log("\n[4] 非法值被拒");
{
  const bad1 = await call("/api/admin/config", { method: "POST", body: { operationUrl: "ftp://x.com" } });
  ok("非 http(s) 被拒", bad1.status === 400, `got ${bad1.status}`);

  const bad2 = await call("/api/admin/config", { method: "POST", body: { hiddenGestureClicks: 2 } });
  ok("手势过少被拒", bad2.status === 400, `got ${bad2.status}`);

  const bad3 = await call("/api/admin/config", { method: "POST", body: { hiddenGestureClicks: 50 } });
  ok("手势过多被拒", bad3.status === 400, `got ${bad3.status}`);

  const bad4 = await call("/api/admin/config", { method: "POST", body: { adminPassword: "12" } });
  ok("口令过短被拒", bad4.status === 400, `got ${bad4.status}`);
}

console.log("\n[5] 可以清空操作地址");
{
  const r = await call("/api/admin/config", { method: "POST", body: { operationUrl: "" } });
  ok("清空成功", r.status === 200 && r.json?.config?.operationUrl === "", JSON.stringify(r.json?.config?.operationUrl));
}

console.log(`\n${failures === 0 ? "✅ 全部通过" : `❌ ${failures} 项失败`}\n`);
process.exit(failures === 0 ? 0 : 1);
