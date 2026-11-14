// 管理后台端到端测试：初始化、登录、鉴权、越权、限速、设备操作
const BASE = process.env.BASE || "http://127.0.0.1:8081";

let failures = 0;
function ok(name, cond, extra = "") {
  console.log(`  ${cond ? "✅" : "❌"} ${name}${cond ? "" : " " + extra}`);
  if (!cond) failures++;
}

let cookie = "";

async function call(path, { method = "GET", body, withCookie = true, raw, headers: extra } = {}) {
  const headers = { ...(extra || {}) };
  if (body) headers["Content-Type"] = "application/json";
  if (withCookie && cookie) headers["Cookie"] = cookie;
  const res = await fetch(BASE + path, {
    method,
    headers,
    body: body ? JSON.stringify(body) : raw,
    redirect: "manual",
  });
  const setCookie = res.headers.get("set-cookie");
  if (setCookie) cookie = setCookie.split(";")[0];
  const text = await res.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    /* 可能返回 HTML */
  }
  return { status: res.status, json, text, headers: res.headers };
}

console.log("\n[1] 网页可访问");
{
  const r = await call("/");
  ok("返回 200", r.status === 200, `got ${r.status}`);
  ok("是 HTML", (r.headers.get("content-type") || "").includes("text/html"), r.headers.get("content-type"));
  ok("包含后台标题", r.text.includes("设备管理后台"));
  ok("包含登录界面", r.text.includes("管理员登录"));
}

console.log("\n[2] 初始状态：尚未创建管理员");
{
  const r = await call("/api/admin/status");
  ok("status 可用", r.status === 200, `got ${r.status}`);
  ok("initialized=false", r.json?.initialized === false, JSON.stringify(r.json));
}

console.log("\n[3] 未登录不能访问设备列表");
{
  const r = await call("/api/admin/devices", { withCookie: false });
  ok("返回 401", r.status === 401, `got ${r.status}`);
  ok("error=not_logged_in", r.json?.error === "not_logged_in", JSON.stringify(r.json));
}

console.log("\n[4] 弱密码被拒绝");
{
  const r = await call("/api/admin/setup", { method: "POST", body: { username: "admin", password: "1234567890" } });
  ok("纯数字被拒 400", r.status === 400, `got ${r.status}`);
  const r2 = await call("/api/admin/setup", { method: "POST", body: { username: "admin", password: "short" } });
  ok("过短被拒 400", r2.status === 400, `got ${r2.status}`);
}

console.log("\n[5] 创建管理员");
{
  const r = await call("/api/admin/setup", {
    method: "POST",
    body: { username: "admin", password: "Str0ngPassw0rd!" },
  });
  ok("创建成功", r.status === 200 && r.json?.ok === true, JSON.stringify(r.json));
  ok("下发了会话 Cookie", cookie.startsWith("guardian_session="), cookie);
}

console.log("\n[6] 已有管理员后不能重复初始化");
{
  const r = await call("/api/admin/setup", {
    method: "POST",
    body: { username: "hacker", password: "AnotherP@ss123" },
    withCookie: false,
  });
  ok("被拒 403", r.status === 403, `got ${r.status}`);
}

console.log("\n[7] 已登录可访问");
{
  const me = await call("/api/admin/me");
  ok("拿到自己的信息", me.json?.username === "admin", JSON.stringify(me.json));
  const list = await call("/api/admin/devices");
  ok("设备列表可访问", list.status === 200 && Array.isArray(list.json?.devices), JSON.stringify(list.json).slice(0, 120));
  ok("返回统计", typeof list.json?.stats?.total === "number");
}

console.log("\n[8] 错误密码被拒");
{
  const saved = cookie;
  cookie = "";
  const r = await call("/api/admin/login", { method: "POST", body: { username: "admin", password: "wrong-password" } });
  ok("返回 401", r.status === 401, `got ${r.status}`);
  ok("不泄露账号是否存在", r.json?.message === "用户名或密码错误", JSON.stringify(r.json));
  cookie = saved;
}

console.log("\n[9] 登出后会话失效");
{
  const saved = cookie;
  await call("/api/admin/logout", { method: "POST", body: {} });
  const r = await call("/api/admin/devices");
  ok("登出后 401", r.status === 401, `got ${r.status}`);
  cookie = saved; // 恢复以便后续测试
}

console.log("\n[10] 正常登录");
{
  cookie = "";
  const r = await call("/api/admin/login", { method: "POST", body: { username: "admin", password: "Str0ngPassw0rd!" } });
  ok("登录成功", r.status === 200 && r.json?.ok === true, JSON.stringify(r.json));
  ok("拿到新会话", cookie.startsWith("guardian_session="));
}

console.log("\n[11] 管理端能给设备下发指令");
{
  // 先通过监控端接口造一台设备
  const KEY = "web-admin-test-key-1234";
  const codeRes = await call("/api/pairing/code", {
    method: "POST",
    body: {},
    withCookie: false,
    headers: { Authorization: `Bearer ${KEY}` },
  });
  const code = codeRes.json?.code;
  ok("配对码已生成", /^\d{6}$/.test(code || ""), JSON.stringify(codeRes.json));

  const claim = await call("/api/pairing/claim", {
    method: "POST",
    body: { code, deviceName: "网页测试机", model: "TestPhone", appVersion: "1.0" },
    withCookie: false,
  });
  ok("设备已配对", !!claim.json?.deviceId, JSON.stringify(claim.json));

  const list = await call("/api/admin/devices");
  const dev = (list.json?.devices || []).find((d) => d.id === claim.json?.deviceId);
  ok("后台能看到这台设备", !!dev, "未找到");
  ok("初始离线", dev?.online === false);
  ok("能看到所属 Key 前缀", typeof dev?.ownerKeyHashPrefix === "string" && dev.ownerKeyHashPrefix.length > 0);

  // 离线设备下发指令应明确失败而不是 500
  const cmd = await call(`/api/admin/devices/${claim.json.deviceId}/locate`, { method: "POST", body: {} });
  ok("离线指令返回 ok:false", cmd.status === 200 && cmd.json?.ok === false, JSON.stringify(cmd.json));
  ok("给出可读原因", typeof cmd.json?.message === "string" && cmd.json.message.length > 0, JSON.stringify(cmd.json));

  // 解绑
  const del = await call(`/api/admin/devices/${claim.json.deviceId}`, { method: "DELETE" });
  ok("可以解绑设备", del.status === 200 && del.json?.ok === true, JSON.stringify(del.json));
}

console.log("\n[12] 路径穿越防护");
{
  const r = await call("/../server/src/db.js", { withCookie: false });
  ok("不能读到源码", r.status === 404 || !r.text.includes("monitors:"), `status=${r.status}`);
  const r2 = await fetch(BASE + "/..%2f..%2fetc%2fpasswd").catch(() => null);
  if (r2) {
    const t = await r2.text();
    ok("编码穿越被挡", !t.includes("root:"), "读到系统文件");
  } else {
    ok("编码穿越被挡", true);
  }
}

console.log("\n[13] 登录限速");
{
  cookie = "";
  let blocked = false;
  for (let i = 0; i < 12; i++) {
    const r = await call("/api/admin/login", { method: "POST", body: { username: "admin", password: "bad-" + i } });
    if (r.status === 429) {
      blocked = true;
      break;
    }
  }
  ok("连续失败后被限速", blocked, "没有触发 429");
}

console.log(`\n${failures === 0 ? "✅ 全部通过" : `❌ ${failures} 项失败`}\n`);
process.exit(failures === 0 ? 0 : 1);
