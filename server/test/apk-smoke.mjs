// APK 分发与批量配对码测试
const BASE = process.env.BASE || "http://127.0.0.1:8091";
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
  const buf = Buffer.from(await r.arrayBuffer());
  let json = null;
  try {
    json = JSON.parse(buf.toString("utf8"));
  } catch {
    /* 二进制或 html */
  }
  return { status: r.status, json, buf, headers: r.headers };
}

console.log("\n[准备] 创建管理员");
{
  const r = await call("/api/admin/setup", { method: "POST", body: { username: "admin", password: "ApkTest@2026!" }, withCookie: false });
  ok("管理员就绪", r.status === 200 || r.status === 403, `status=${r.status}`);
  if (r.status === 403) {
    await call("/api/admin/login", { method: "POST", body: { username: "admin", password: "ApkTest@2026!" }, withCookie: false });
  }
}

console.log("\n[1] 未登录不能上传");
{
  const r = await fetch(BASE + "/api/admin/apks?name=x.apk", {
    method: "POST",
    headers: { "Content-Type": "application/octet-stream" },
    body: Buffer.from("PK\x03\x04fake"),
  });
  ok("未登录上传被拒 401", r.status === 401, `got ${r.status}`);
}

console.log("\n[2] 上传 APK");
{
  // 构造一个最小的合法 zip 头（PK\x03\x04）
  const fakeApk = Buffer.concat([Buffer.from([0x50, 0x4b, 0x03, 0x04]), Buffer.alloc(5000, 1)]);
  const r = await fetch(BASE + "/api/admin/apks?name=Guardian.apk", {
    method: "POST",
    headers: { "Cookie": cookie, "Content-Type": "application/octet-stream" },
    body: fakeApk,
  });
  const j = await r.json();
  ok("上传成功", r.status === 200 && j.ok === true, JSON.stringify(j));
  ok("返回下载链接", typeof j.url === "string" && j.url.includes("/download/"), j.url);
  ok("记录了大小", j.size === fakeApk.length, `${j.size}`);
}

console.log("\n[3] 列表与地址");
{
  const r = await call("/api/admin/apks");
  ok("列表可读", r.status === 200 && Array.isArray(r.json?.apks));
  ok("含刚上传的文件", (r.json?.apks || []).some((a) => a.name === "Guardian.apk"));
  ok("提供了 baseUrl", typeof r.json?.baseUrl === "string", r.json?.baseUrl);
}

console.log("\n[4] 公开下载（无需登录）");
{
  const r = await fetch(BASE + "/download/Guardian.apk");
  const buf = Buffer.from(await r.arrayBuffer());
  ok("无需登录可下载", r.status === 200, `got ${r.status}`);
  ok("Content-Type 正确", (r.headers.get("content-type") || "").includes("android.package-archive"), r.headers.get("content-type"));
  ok("Content-Disposition 是 attachment", (r.headers.get("content-disposition") || "").includes("attachment"));
  ok("内容完整", buf.length === 5004 && buf[0] === 0x50 && buf[1] === 0x4b, `len=${buf.length}`);
}

console.log("\n[5] 安装页");
{
  const r = await call("/install", { withCookie: false });
  ok("安装页可访问", r.status === 200);
  const html = r.buf.toString("utf8");
  ok("是 HTML", (r.headers.get("content-type") || "").includes("text/html"));
  ok("列出了安装包", html.includes("Guardian.apk"));
  ok("含下载链接", html.includes("/download/"));
}

console.log("\n[6] 文件名安全");
{
  // 路径穿越尝试
  const evil = await fetch(BASE + "/download/..%2f..%2fdb.json");
  ok("路径穿越被挡", evil.status === 404, `got ${evil.status}`);

  const notApk = await fetch(BASE + "/api/admin/apks?name=evil.exe", {
    method: "POST",
    headers: { "Cookie": cookie, "Content-Type": "application/octet-stream" },
    body: Buffer.from([0x50, 0x4b, 0x03, 0x04, 1, 2, 3]),
  });
  ok("非 .apk 被拒", notApk.status === 400, `got ${notApk.status}`);

  const badContent = await fetch(BASE + "/api/admin/apks?name=bad.apk", {
    method: "POST",
    headers: { "Cookie": cookie, "Content-Type": "application/octet-stream" },
    body: Buffer.from("not a zip file at all"),
  });
  ok("非 zip 内容被拒", badContent.status === 400, `got ${badContent.status}`);
}

console.log("\n[7] 批量配对码");
{
  const r = await call("/api/admin/pairing/batch", { method: "POST", body: { count: 8 } });
  const codes = r.json?.codes || [];
  ok("生成了 8 个", codes.length === 8, `got ${codes.length}`);
  ok("都是 6 位数字", codes.every((c) => /^\d{6}$/.test(c)), codes.join(","));
  ok("互不重复", new Set(codes).size === codes.length);

  // 验证一个码真的能用来配对
  const claim = await fetch(BASE + "/api/pairing/claim", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code: codes[0], deviceName: "PDA-01" }),
  });
  const cj = await claim.json();
  ok("配对码可用", claim.status === 200 && !!cj.deviceId, JSON.stringify(cj));

  // 二次使用应失败
  const again = await fetch(BASE + "/api/pairing/claim", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code: codes[0], deviceName: "PDA-02" }),
  });
  ok("一码只能用一次", again.status === 404, `got ${again.status}`);

  // 数量上限
  const big = await call("/api/admin/pairing/batch", { method: "POST", body: { count: 999 } });
  ok("超过 50 被限制", (big.json?.codes || []).length === 50, `got ${(big.json?.codes || []).length}`);
}

console.log("\n[8] 删除安装包");
{
  const r = await call("/api/admin/apks/Guardian.apk", { method: "DELETE" });
  ok("删除成功", r.status === 200);
  const after = await fetch(BASE + "/download/Guardian.apk");
  ok("删除后无法下载", after.status === 404, `got ${after.status}`);
}

console.log(`\n${failures === 0 ? "✅ 全部通过" : `❌ ${failures} 项失败`}\n`);
process.exit(failures === 0 ? 0 : 1);
