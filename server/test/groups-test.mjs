// 分组功能测试
const BASE = process.env.BASE || "http://127.0.0.1:8101";
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
  return { status: r.status, json };
}

console.log("\n[准备]");
{
  await call("/api/admin/setup", { method: "POST", body: { username: "admin", password: "GroupTest@2026" } });
  ok("管理员就绪", !!cookie);

  // 造一台设备
  const KEY = "group-test-key-123456789";
  const codeRes = await fetch(BASE + "/api/pairing/code", {
    method: "POST",
    headers: { Authorization: `Bearer ${KEY}`, "Content-Type": "application/json" },
    body: "{}",
  });
  const code = (await codeRes.json()).code;
  const claim = await fetch(BASE + "/api/pairing/claim", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code, deviceName: "分组测试机" }),
  });
  const cj = await claim.json();
  globalThis.DEV = cj.deviceId;
  ok("设备已配对", !!cj.deviceId);
}

console.log("\n[1] 创建分组");
let gid;
{
  const r = await call("/api/admin/groups", { method: "POST", body: { name: "一号仓库" } });
  ok("创建成功", r.status === 200 && r.json?.ok === true, JSON.stringify(r.json).slice(0, 100));
  gid = r.json?.group?.id;
  ok("返回 ID", !!gid);
  ok("有颜色", !!r.json?.group?.color);
}

console.log("\n[2] 重名与非法名被拒");
{
  const dup = await call("/api/admin/groups", { method: "POST", body: { name: "一号仓库" } });
  ok("重名被拒", dup.status === 400, `got ${dup.status}`);

  const empty = await call("/api/admin/groups", { method: "POST", body: { name: "" } });
  ok("空名被拒", empty.status === 400, `got ${empty.status}`);

  const long = await call("/api/admin/groups", { method: "POST", body: { name: "这是一个超过二十个字的超长分组名称测试用例" } });
  ok("超长被拒", long.status === 400, `got ${long.status}`);
}

console.log("\n[3] 列表与统计");
{
  await call("/api/admin/groups", { method: "POST", body: { name: "二号仓库" } });

  const r = await call("/api/admin/groups");
  ok("列出 2 个", (r.json?.groups || []).length === 2, `got ${(r.json?.groups || []).length}`);
  ok("含设备数统计", (r.json?.groups || []).every((g) => "deviceCount" in g));
  ok("含颜色列表", Array.isArray(r.json?.colors));
}

console.log("\n[4] 分配设备");
{
  const r = await call(`/api/admin/devices/${globalThis.DEV}/group`, { method: "POST", body: { groupId: gid } });
  ok("分配成功", r.status === 200 && r.json?.groupId === gid, JSON.stringify(r.json));

  const list = await call("/api/admin/devices");
  const dev = (list.json?.devices || []).find((d) => d.id === globalThis.DEV);
  ok("设备带分组信息", dev?.group?.id === gid, JSON.stringify(dev?.group));
  ok("分组名正确", dev?.group?.name === "一号仓库");

  const groups = await call("/api/admin/groups");
  const g = (groups.json?.groups || []).find((x) => x.id === gid);
  ok("分组设备数更新", g?.deviceCount === 1, `got ${g?.deviceCount}`);
}

console.log("\n[5] 按分组筛选");
{
  const r = await call(`/api/admin/devices?groupId=${gid}`);
  ok("筛选出 1 台", (r.json?.devices || []).length === 1);

  const none = await call("/api/admin/devices?groupId=none");
  ok("未分组筛选可用", none.status === 200);
}

console.log("\n[6] 移出分组");
{
  const r = await call(`/api/admin/devices/${globalThis.DEV}/group`, { method: "POST", body: { groupId: null } });
  ok("移出成功", r.status === 200 && r.json?.groupId === null);

  const list = await call("/api/admin/devices");
  const dev = (list.json?.devices || []).find((d) => d.id === globalThis.DEV);
  ok("分组已清空", dev?.group === null);
}

console.log("\n[7] 预置注册指定分组");
{
  const rotate = await call("/api/admin/enrollment/rotate", { method: "POST", body: {} });
  const groupKey = rotate.json?.groupKey;

  const save = await call("/api/admin/enrollment", {
    method: "POST",
    body: { enabled: true, groupKey, groupId: gid, maxDevices: 10 },
  });
  ok("保存带分组", save.status === 200 && save.json?.enrollment?.groupId === gid, JSON.stringify(save.json).slice(0, 120));

  // 用组密钥注册，应该自动归入该分组
  const e = await fetch(BASE + "/api/enroll", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ groupKey, deviceName: "自动归组设备", installId: "auto-group-1" }),
  });
  const ej = await e.json();
  ok("注册成功", e.status === 200, JSON.stringify(ej).slice(0, 100));

  const list = await call("/api/admin/devices");
  const dev = (list.json?.devices || []).find((d) => d.id === ej.deviceId);
  ok("自动归入分组", dev?.group?.id === gid, JSON.stringify(dev?.group));
}

console.log("\n[8] 删除分组不删设备");
{
  const before = (await call("/api/admin/devices")).json?.devices?.length || 0;
  const r = await call(`/api/admin/groups/${gid}`, { method: "DELETE" });
  ok("删除成功", r.status === 200);
  ok("报告解绑数量", typeof r.json?.detached === "number");

  const after = (await call("/api/admin/devices")).json?.devices?.length || 0;
  ok("设备没被删", after === before, `${before} -> ${after}`);

  const list = await call("/api/admin/devices");
  const dev = (list.json?.devices || []).find((d) => d.id === globalThis.DEV);
  ok("设备的组已清空", dev?.group === null);
}

console.log(`\n${failures === 0 ? "✅ 全部通过" : `❌ ${failures} 项失败`}\n`);
process.exit(failures === 0 ? 0 : 1);
