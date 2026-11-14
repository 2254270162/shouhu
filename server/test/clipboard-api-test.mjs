// 剪贴板接口测试
//
// 用法：
//   BASE=http://127.0.0.1:8080 DEV_ID=dev_xxx ADMIN_USER=admin ADMIN_PASS=xxx node test/clipboard-api-test.mjs
// 也可以直接提供 ADMIN_COOKIE 跳过登录
const BASE = process.env.BASE || "http://127.0.0.1:8080";
const DEV = process.env.DEV_ID || "";
const ADMIN_USER = process.env.ADMIN_USER || "admin";
const ADMIN_PASS = process.env.ADMIN_PASS || "";
const ADMIN_COOKIE = process.env.ADMIN_COOKIE || "";

if (!DEV) {
  console.log("用法: BASE=http://服务器地址 DEV_ID=设备ID ADMIN_USER=用户名 ADMIN_PASS=密码 node test/clipboard-api-test.mjs");
  console.log("（也可以直接提供 ADMIN_COOKIE 跳过登录）");
  process.exit(1);
}

const login = await fetch(BASE + "/api/admin/login", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ username: ADMIN_USER, password: ADMIN_PASS }),
});
console.log("登录:", login.status);

const cookie = ADMIN_COOKIE || (login.headers.get("set-cookie") || "").split(";")[0];
if (!cookie) {
  console.log("无法登录，跳过接口测试");
  process.exit(0);
}

const r = await fetch(`${BASE}/api/admin/devices/${DEV}/clipboard`, {
  method: "POST",
  headers: { Cookie: cookie, "Content-Type": "application/json" },
  body: "{}",
});
const j = await r.json();
console.log("\n剪贴板结果:", JSON.stringify(j, null, 2).slice(0, 800));
