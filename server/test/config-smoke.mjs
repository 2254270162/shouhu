// 权限上报 + 配置下发测试
const BASE = process.env.BASE || "http://127.0.0.1:8092";
const KEY = "cfg-test-key-123456789";
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
    /* html */
  }
  return { status: r.status, json, text };
}

async function dev(path, { method = "POST", body, token } = {}) {
  const headers = { "X-Device-Token": token };
  if (body) headers["Content-Type"] = "application/json";
  const r = await fetch(BASE + path, { method, headers, body: body ? JSON.stringify(body) : undefined });
  const text = await r.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    /* 非 JSON */
  }
  return { status: r.status, json };
}

console.log("\n[准备]");
{
  const s = await call("/api/admin/setup", { method: "POST", body: { username: "admin", password: "ConfigTest@2026" }, withCookie: false });
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
    body: JSON.stringify({ code, deviceName: "PDA-TEST" }),
  });
  const cj = await claim.json();
  globalThis.DEV = cj.deviceId;
  globalThis.TOK = cj.deviceToken;
  ok("设备已配对", !!cj.deviceId);
}

const DEV = globalThis.DEV;
const TOK = globalThis.TOK;

console.log("\n[1] 权限状态上报");
{
  const telemetry = {
    battery: { level: 60 },
    permissions: {
      items: [
        { key: "location", label: "定位", granted: true, special: false },
        { key: "camera", label: "相机", granted: false, special: false },
        { key: "notification", label: "通知", granted: false, special: false },
        { key: "usage_access", label: "使用情况访问", granted: false, special: true },
      ],
      missingCount: 3,
      missingLabels: "相机、通知、使用情况访问",
      complete: false,
    },
  };
  const r = await dev(`/api/devices/${DEV}/telemetry`, { body: { telemetry }, token: TOK });
  ok("上报成功", r.status === 200, JSON.stringify(r.json).slice(0, 80));
  ok("回传了配置", r.json?.config != null, JSON.stringify(r.json?.config));

  const d = await call(`/api/admin/devices/${DEV}`);
  ok("权限状态已保存", d.json?.device?.permissions != null);
  ok("缺失数量正确", d.json?.device?.permissions?.missingCount === 3, String(d.json?.device?.permissions?.missingCount));
  ok("缺失标签正确", (d.json?.device?.permissions?.missingLabels || "").includes("相机"));
}

console.log("\n[2] 权限状态在设备列表里可见");
{
  const list = await call("/api/admin/devices");
  const dev = (list.json?.devices || []).find((x) => x.id === DEV);
  ok("列表含权限字段", dev?.permissions != null, JSON.stringify(dev?.permissions || null).slice(0, 80));
}

console.log("\n[3] 全局配置");
{
  const g = await call("/api/admin/config");
  ok("能读全局配置", g.status === 200 && g.json?.config != null);
  ok("默认是 hybrid", g.json?.config?.backgroundMode === "hybrid", g.json?.config?.backgroundMode);
  ok("返回可选模式", Array.isArray(g.json?.modes) && g.json.modes.length === 3);

  const set = await call("/api/admin/config", { method: "POST", body: { backgroundMode: "background", reportIntervalSec: 120 } });
  ok("修改全局成功", set.status === 200, JSON.stringify(set.json).slice(0, 100));
  ok("模式已更新", set.json?.config?.backgroundMode === "background");
  ok("间隔已更新", set.json?.config?.reportIntervalSec === 120);
}

console.log("\n[4] 非法配置被拒");
{
  const bad1 = await call("/api/admin/config", { method: "POST", body: { backgroundMode: "hacker" } });
  ok("非法模式被拒 400", bad1.status === 400, `got ${bad1.status}`);

  const bad2 = await call("/api/admin/config", { method: "POST", body: { reportIntervalSec: 5 } });
  ok("间隔过小被拒 400", bad2.status === 400, `got ${bad2.status}`);

  const bad3 = await call("/api/admin/config", { method: "POST", body: { reportIntervalSec: 999999 } });
  ok("间隔过大被拒 400", bad3.status === 400, `got ${bad3.status}`);

  const empty = await call("/api/admin/config", { method: "POST", body: {} });
  ok("空改动被拒 400", empty.status === 400, `got ${empty.status}`);
}

console.log("\n[5] 设备级配置覆盖全局");
{
  const set = await call("/api/admin/config", {
    method: "POST",
    body: { deviceId: DEV, backgroundMode: "foreground" },
  });
  ok("设置设备级成功", set.status === 200, JSON.stringify(set.json).slice(0, 100));
  ok("设备级生效", set.json?.config?.backgroundMode === "foreground", set.json?.config?.backgroundMode);

  // 设备自己拉取
  const cfg = await dev(`/api/devices/${DEV}/config`, { method: "GET", token: TOK });
  ok("设备能拉到配置", cfg.status === 200 && cfg.json?.ok === true);
  ok("拿到设备级的值", cfg.json?.config?.backgroundMode === "foreground", cfg.json?.config?.backgroundMode);
  ok("其余字段继承全局", cfg.json?.config?.reportIntervalSec === 120, String(cfg.json?.config?.reportIntervalSec));
}

console.log("\n[6] 批量设置");
{
  const r = await call("/api/admin/config/bulk", {
    method: "POST",
    body: { deviceIds: [DEV], backgroundMode: "hybrid", reportIntervalSec: 600 },
  });
  ok("批量设置成功", r.status === 200 && r.json?.changed === 1, JSON.stringify(r.json));

  const cfg = await dev(`/api/devices/${DEV}/config`, { method: "GET", token: TOK });
  ok("批量值已生效", cfg.json?.config?.backgroundMode === "hybrid", cfg.json?.config?.backgroundMode);
  ok("间隔已生效", cfg.json?.config?.reportIntervalSec === 600, String(cfg.json?.config?.reportIntervalSec));

  const noTarget = await call("/api/admin/config/bulk", { method: "POST", body: { deviceIds: [] } });
  ok("空设备列表被拒", noTarget.status === 400, `got ${noTarget.status}`);
}

console.log("\n[7] 未授权访问");
{
  const r = await fetch(BASE + "/api/admin/config");
  ok("未登录读配置 401", r.status === 401, `got ${r.status}`);
}

console.log(`\n${failures === 0 ? "✅ 全部通过" : `❌ ${failures} 项失败`}\n`);
process.exit(failures === 0 ? 0 : 1);
