// 监控端全链路测试：登录 → 设备 → 分组 → 打开管理页 → 预置注册 → 编译
const BASE = process.env.BASE || "http://127.0.0.1:8110";
let cookie = "";
let failures = 0;
const ok = (n, c, e = "") => {
  console.log(`  ${c ? "✅" : "❌"} ${n}${c ? "" : " " + e}`);
  if (!c) failures++;
};

async function call(path, { method = "GET", body } = {}) {
  const headers = {};
  if (body) headers["Content-Type"] = "application/json";
  if (cookie) headers["Cookie"] = cookie;
  const r = await fetch(BASE + path, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined,
  });
  const sc = r.headers.get("set-cookie");
  if (sc) cookie = sc.split(";")[0];
  const text = await r.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    /* ignore */
  }
  return { status: r.status, json };
}

console.log("\n[1] 账号密码登录（监控端用的方式）");
{
  const setup = await call("/api/admin/setup", {
    method: "POST",
    body: { username: "admin", password: "MonitorFlow@2026" },
  });
  ok("管理员初始化", setup.status === 200 || setup.status === 409, `HTTP ${setup.status}`);

  const login = await call("/api/admin/login", {
    method: "POST",
    body: { username: "admin", password: "MonitorFlow@2026" },
  });
  ok("登录成功", login.status === 200 && login.json?.ok === true, `HTTP ${login.status}`);
  ok("拿到 Session Cookie", cookie.includes("=") && cookie.length > 20, cookie.slice(0, 30));

  const status = await call("/api/admin/status");
  ok("Session 有效", status.status === 200, `HTTP ${status.status}`);

  const bad = await fetch(BASE + "/api/admin/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username: "admin", password: "wrong" }),
  });
  ok("错误密码被拒", bad.status === 401, `HTTP ${bad.status}`);
}

console.log("\n[2] 设备列表与配对");
{
  const KEY = "monitor-flow-key-123456789";
  const codeRes = await fetch(BASE + "/api/pairing/code", {
    method: "POST",
    headers: { Authorization: `Bearer ${KEY}`, "Content-Type": "application/json" },
    body: "{}",
  });
  const code = (await codeRes.json()).code;
  const claim = await fetch(BASE + "/api/pairing/claim", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code, deviceName: "监控端测试机" }),
  });
  const cj = await claim.json();
  globalThis.DEV = cj.deviceId;
  globalThis.TOK = cj.deviceToken;

  const list = await call("/api/admin/devices");
  ok("设备列表可读", list.status === 200);
  ok("含分组字段", "group" in ((list.json?.devices || [])[0] || {}));
}

console.log("\n[3] 分组功能");
let gid;
{
  const create = await call("/api/admin/groups", { method: "POST", body: { name: "监控端分组" } });
  ok("创建分组", create.status === 200, JSON.stringify(create.json).slice(0, 80));
  gid = create.json?.group?.id;

  const groups = await call("/api/admin/groups");
  ok("分组列表", groups.status === 200 && (groups.json?.groups || []).length > 0);

  const assign = await call(`/api/admin/devices/${globalThis.DEV}/group`, {
    method: "POST",
    body: { groupId: gid },
  });
  ok("设备归组", assign.status === 200 && assign.json?.groupId === gid);
}

console.log("\n[4] 打开管理页（服务端下发指令）");
{
  // 设备不在线时应明确失败，不能假装成功
  const r = await call(`/api/admin/devices/${globalThis.DEV}/open-admin`, { method: "POST", body: {} });
  ok("接口存在", r.status === 200, `HTTP ${r.status}`);
  ok("离线时明确提示", r.json?.ok === false && ((r.json?.message || "").includes("不在线") || (r.json?.message || "").includes("离线")), JSON.stringify(r.json));
}

console.log("\n[5] 预置注册");
{
  const rot = await call("/api/admin/enrollment/rotate", { method: "POST", body: {} });
  const gk = rot.json?.groupKey;
  ok("生成组密钥", !!gk && gk.length >= 16, `len=${gk?.length}`);

  const save = await call("/api/admin/enrollment", {
    method: "POST",
    body: { enabled: true, groupKey: gk, serverUrl: "https://example.com", maxDevices: 10, groupId: gid },
  });
  ok("保存配置", save.status === 200, JSON.stringify(save.json).slice(0, 100));

  const read = await call("/api/admin/enrollment");
  ok("读回配置", read.json?.enrollment?.enabled === true);
  ok("分组已存", read.json?.enrollment?.groupId === gid);
}

console.log("\n[6] 编译接口");
{
  const env = await call("/api/admin/build/env");
  ok("构建环境可读", env.status === 200);

  const status = await call("/api/admin/build/status");
  ok("编译状态可读", status.status === 200);

  // 未填地址时应该被拒（上一轮加的校验）
  await call("/api/admin/enrollment", {
    method: "POST",
    body: { enabled: true, groupKey: "TESTKEY1234567890AB", serverUrl: "", maxDevices: 0 },
  });
  const build = await call("/api/admin/build", { method: "POST", body: { module: "device", enroll: true } });
  ok("无地址时拒绝编译", build.status === 400, `HTTP ${build.status}`);
  ok("提示填地址", (build.json?.message || "").includes("服务器地址"), build.json?.message);
}

console.log("\n[7] 退出登录");
{
  const out = await call("/api/admin/logout", { method: "POST", body: {} });
  ok("退出成功", out.status === 200);

  const after = await fetch(BASE + "/api/admin/devices", {
    headers: { Cookie: cookie },
  });
  ok("Session 已失效", after.status === 401, `HTTP ${after.status}`);
}

console.log(`\n${failures === 0 ? "✅ 全部通过" : `❌ ${failures} 项失败`}\n`);
process.exit(failures === 0 ? 0 : 1);
