// 预置注册（组密钥）测试
const BASE = process.env.BASE || "http://127.0.0.1:8100";
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

async function enroll(body) {
  const r = await fetch(BASE + "/api/enroll", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
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
  const s = await call("/api/admin/setup", { method: "POST", body: { username: "admin", password: "EnrollTest@2026" }, withCookie: false });
  ok("管理员就绪", s.status === 200);
}

console.log("\n[1] 默认未启用");
{
  const g = await call("/api/admin/enrollment");
  ok("可读配置", g.status === 200);
  ok("默认关闭", g.json?.enrollment?.enabled === false);

  const e = await enroll({ groupKey: "whatever123456789", deviceName: "测试" });
  ok("未启用时注册被拒", e.status === 403, `got ${e.status}`);
  ok("提示用配对码", (e.json?.message || "").includes("配对码"), e.json?.message);
}

console.log("\n[2] 生成组密钥");
let groupKey;
{
  const r = await call("/api/admin/enrollment/rotate", { method: "POST", body: {} });
  groupKey = r.json?.groupKey;
  ok("生成成功", !!groupKey, JSON.stringify(r.json));
  ok("长度足够", (groupKey || "").length >= 16, `len=${groupKey?.length}`);
}

console.log("\n[3] 启用");
{
  const r = await call("/api/admin/enrollment", {
    method: "POST",
    body: { enabled: true, groupKey, maxDevices: 5 },
  });
  ok("启用成功", r.status === 200 && r.json?.enrollment?.enabled === true, JSON.stringify(r.json).slice(0, 100));
  ok("上限已存", r.json?.enrollment?.maxDevices === 5);
}

console.log("\n[4] 自动注册");
{
  const e = await enroll({
    groupKey,
    deviceName: "PDA-001",
    installId: "install-aaa",
    model: "TestPDA",
  });
  ok("注册成功", e.status === 200 && e.json?.ok === true, JSON.stringify(e.json).slice(0, 120));
  ok("拿到设备 ID", !!e.json?.deviceId);
  ok("拿到独立令牌", !!e.json?.deviceToken);
  globalThis.DEV1 = e.json?.deviceId;
  globalThis.TOK1 = e.json?.deviceToken;
}

console.log("\n[5] 同一设备重复注册应复用");
{
  const e = await enroll({
    groupKey,
    deviceName: "PDA-001-改名",
    installId: "install-aaa",
  });
  ok("复用原记录", e.json?.reused === true, JSON.stringify(e.json).slice(0, 100));
  ok("设备 ID 不变", e.json?.deviceId === globalThis.DEV1, `${e.json?.deviceId} vs ${globalThis.DEV1}`);
}

console.log("\n[6] 不同设备拿到不同令牌");
{
  const e = await enroll({ groupKey, deviceName: "PDA-002", installId: "install-bbb" });
  ok("注册成功", e.status === 200);
  ok("设备 ID 不同", e.json?.deviceId !== globalThis.DEV1);
  ok("令牌不同", e.json?.deviceToken !== globalThis.TOK1);
}

console.log("\n[7] 错误密钥被拒");
{
  const e = await enroll({ groupKey: "wrong-key-1234567890", deviceName: "黑客" });
  ok("被拒 401", e.status === 401, `got ${e.status}`);
}

console.log("\n[8] 注册上限");
{
  // 已有 2 台，上限 5，再注册 3 台应该成功，第 6 台失败
  for (let i = 3; i <= 5; i++) {
    const e = await enroll({ groupKey, deviceName: `PDA-00${i}`, installId: `install-${i}` });
    if (e.status !== 200) {
      ok(`第 ${i} 台应成功`, false, JSON.stringify(e.json));
      break;
    }
  }
  const overflow = await enroll({ groupKey, deviceName: "PDA-006", installId: "install-6" });
  ok("超出上限被拒", overflow.status === 403, `got ${overflow.status}`);
  ok("提示上限", (overflow.json?.message || "").includes("上限"), overflow.json?.message);
}

console.log("\n[9] 自动注册的设备可用");
{
  const r = await fetch(`${BASE}/api/devices/${globalThis.DEV1}/config`, {
    headers: { "X-Device-Token": globalThis.TOK1 },
  });
  ok("能拉配置", r.status === 200);
}

console.log("\n[10] 停用后不能再注册");
{
  await call("/api/admin/enrollment", { method: "POST", body: { enabled: false } });
  const e = await enroll({ groupKey, deviceName: "PDA-007", installId: "install-7" });
  ok("停用后被拒", e.status === 403, `got ${e.status}`);
}

console.log(`\n${failures === 0 ? "✅ 全部通过" : `❌ ${failures} 项失败`}\n`);
process.exit(failures === 0 ? 0 : 1);
