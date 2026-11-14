// 预置注册接口检查
//
// 用法：BASE=http://127.0.0.1:8080 ADMIN_USER=admin ADMIN_PASS=xxx node test/enroll-api-check.mjs
const BASE = process.env.BASE || "http://127.0.0.1:8080";
const ADMIN_USER = process.env.ADMIN_USER || "admin";
const ADMIN_PASS = process.env.ADMIN_PASS || "";

const h = await fetch(BASE + "/api/health");
console.log("健康:", h.status);

const login = await fetch(BASE + "/api/admin/login", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ username: ADMIN_USER, password: ADMIN_PASS }),
});
const cookie = (login.headers.get("set-cookie") || "").split(";")[0];
console.log("登录:", login.status);

if (!cookie) {
  console.log("无法登录，只测无鉴权路径");
  const r = await fetch(BASE + "/api/admin/enrollment", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: "{}",
  });
  console.log("未登录 POST enrollment:", r.status, "(401=路由存在, 404=路由不存在)");
  process.exit(0);
}

const g = await fetch(BASE + "/api/admin/enrollment", { headers: { Cookie: cookie } });
console.log("GET enrollment:", g.status, (await g.text()).slice(0, 200));

const p = await fetch(BASE + "/api/admin/enrollment", {
  method: "POST",
  headers: { Cookie: cookie, "Content-Type": "application/json" },
  body: JSON.stringify({ enabled: false, groupKey: "TESTKEY123456789012", maxDevices: 0, serverUrl: "" }),
});
console.log("POST enrollment:", p.status, (await p.text()).slice(0, 200));
