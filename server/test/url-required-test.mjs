const BASE = "http://127.0.0.1:8102";
let cookie = "";
async function call(path, opts = {}) {
  const headers = {};
  if (opts.body) headers["Content-Type"] = "application/json";
  if (cookie) headers["Cookie"] = cookie;
  const r = await fetch(BASE + path, { method: opts.method || "GET", headers, body: opts.body ? JSON.stringify(opts.body) : undefined });
  const sc = r.headers.get("set-cookie");
  if (sc) cookie = sc.split(";")[0];
  const t = await r.text();
  let j = null; try { j = JSON.parse(t); } catch {}
  return { status: r.status, json: j };
}
await call("/api/admin/setup", { method: "POST", body: { username: "admin", password: "UrlCheck@2026" } });
const rot = await call("/api/admin/enrollment/rotate", { method: "POST", body: {} });
const key = rot.json.groupKey;
// 启用但不填地址
await call("/api/admin/enrollment", { method: "POST", body: { enabled: true, groupKey: key } });
// 尝试编译带预置注册
const b = await call("/api/admin/build", { method: "POST", body: { module: "device", enroll: true } });
console.log("未填地址编译:", b.status, b.json?.message || b.json?.error);
console.log(b.status === 400 && (b.json?.message||"").includes("服务器地址") ? "✅ 正确拦截" : "❌ 未拦截");
