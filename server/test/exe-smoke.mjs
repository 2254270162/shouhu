// exe 端到端验证：管理员、配对、遥测、日志
const BASE = process.env.BASE || "http://127.0.0.1:8089";
const KEY = "exe-test-key-1234567890";
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
  try { json = JSON.parse(text); } catch { /* html */ }
  return { status: r.status, json, text };
}

console.log("\n[1] 网页与 API");
{
  const p = await call("/", { withCookie: false });
  ok("网页可访问", p.status === 200 && p.text.includes("设备管理后台"));
  const h = await call("/api/health", { withCookie: false });
  ok("健康检查正常", h.status === 200 && h.json?.ok);
}

console.log("\n[2] 管理员");
{
  const s = await call("/api/admin/status", { withCookie: false });
  ok("未初始化", s.json?.initialized === false, JSON.stringify(s.json));

  const setup = await call("/api/admin/setup", { method: "POST", body: { username: "admin", password: "ExeTest@2026!" } });
  ok("创建成功", setup.status === 200, JSON.stringify(setup.json));
  ok("拿到会话", cookie.startsWith("guardian_session="));

  const devices = await call("/api/admin/devices");
  ok("设备列表可读", devices.status === 200 && Array.isArray(devices.json?.devices));
}

console.log("\n[3] 设备配对");
let deviceId, deviceToken;
{
  const codeRes = await fetch(BASE + "/api/pairing/code", {
    method: "POST",
    headers: { Authorization: `Bearer ${KEY}`, "Content-Type": "application/json" },
    body: "{}",
  });
  const code = (await codeRes.json()).code;
  ok("配对码已生成", /^\d{6}$/.test(code || ""));

  const claim = await fetch(BASE + "/api/pairing/claim", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code, deviceName: "EXE测试机", model: "Test", appVersion: "1.0" }),
  });
  const cj = await claim.json();
  deviceId = cj.deviceId;
  deviceToken = cj.deviceToken;
  ok("设备已配对", !!deviceId && !!deviceToken);
}

console.log("\n[4] 遥测上报");
{
  const r = await fetch(BASE + `/api/devices/${deviceId}/telemetry`, {
    method: "POST",
    headers: { "X-Device-Token": deviceToken, "Content-Type": "application/json" },
    body: JSON.stringify({
      telemetry: {
        battery: { level: 55, temperatureC: 30.2, status: "discharging", charging: false },
        screen: { on: true },
        memory: { usedPercent: 50, usedBytes: 100, totalBytes: 200 },
        storage: { usedPercent: 40, availableBytes: 1000 },
        foregroundApp: { available: true, packageName: "com.test", appLabel: "测试App" },
      },
    }),
  });
  ok("上报成功", r.status === 200);

  const d = await call(`/api/admin/devices/${deviceId}`);
  ok("遥测已保存", d.json?.device?.lastTelemetry?.battery?.level === 55, JSON.stringify(d.json?.device?.lastTelemetry || null).slice(0, 80));
  ok("前台应用已记录", d.json?.device?.lastTelemetry?.foregroundApp?.appLabel === "测试App");
}

console.log("\n[5] 日志");
{
  const l = await call(`/api/admin/devices/${deviceId}/logs`);
  const logs = l.json?.logs || [];
  ok("日志非空", logs.length > 0, `count=${logs.length}`);
  const evs = new Set(logs.map((x) => x.event));
  ok("含配对日志", evs.has("pair"), [...evs].join(","));
  ok("含遥测日志", evs.has("telemetry"), [...evs].join(","));
}

console.log("\n[6] 数据持久化");
{
  const d = await call("/api/diag");
  ok("数据目录指向 exe 旁", d.json?.asset?.staticRoot?.includes("exe-test") === true);
}

console.log(`\n${failures === 0 ? "✅ 全部通过" : `❌ ${failures} 项失败`}\n`);
process.exit(failures === 0 ? 0 : 1);
