// 端到端冒烟测试：模拟监控端与设备端的完整流程
import { WebSocket } from "ws";

const BASE = process.env.BASE || "http://127.0.0.1:8080";
const KEY = "monitor-key-1234567890";

let failures = 0;
function check(name, cond, extra = "") {
  if (cond) {
    console.log(`  ✅ ${name}`);
  } else {
    failures++;
    console.log(`  ❌ ${name} ${extra}`);
  }
}

async function api(method, path, { key, deviceToken, body, raw } = {}) {
  const headers = {};
  if (key) headers["Authorization"] = `Bearer ${key}`;
  if (deviceToken) headers["X-Device-Token"] = deviceToken;
  if (body) headers["Content-Type"] = "application/json";
  const res = await fetch(BASE + path, {
    method,
    headers,
    body: body ? JSON.stringify(body) : raw,
  });
  const text = await res.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    /* 非 JSON（照片二进制） */
  }
  return { status: res.status, json, text, headers: res.headers };
}

console.log("\n[1] 健康检查");
{
  const r = await api("GET", "/api/health");
  check("返回 ok", r.status === 200 && r.json?.ok === true, JSON.stringify(r.json));
}

console.log("\n[2] 监控端鉴权");
{
  const r = await api("GET", "/api/devices");
  check("无 Key 被拒 401", r.status === 401, `got ${r.status}`);
  // Key 未注册时应被拒，而不是悄悄建号
  const r2 = await api("GET", "/api/devices", { key: "wrong-key-xxxxxxxx" });
  check("未注册 Key 被拒 401", r2.status === 401, `got ${r2.status}`);
  check("返回可判断的错误码", r2.json?.error === "key_not_registered", JSON.stringify(r2.json));
  const r3 = await api("POST", "/api/monitor/init", { body: { key: "short" } });
  check("弱 Key 被拒 400", r3.status === 400, `got ${r3.status}`);
}

console.log("\n[3] 生成配对码");
let code;
{
  const r = await api("POST", "/api/pairing/code", { key: KEY, body: {} });
  code = r.json?.code;
  check("拿到 6 位配对码", r.status === 200 && /^\d{6}$/.test(code || ""), JSON.stringify(r.json));
}

console.log("\n[4] 设备兑换令牌");
let deviceId, deviceToken;
{
  const r = await api("POST", "/api/pairing/claim", {
    body: { code, deviceName: "测试备用机", model: "Redmi Test", appVersion: "1.0" },
  });
  deviceId = r.json?.deviceId;
  deviceToken = r.json?.deviceToken;
  check("兑换成功", r.status === 200 && !!deviceId && !!deviceToken, JSON.stringify(r.json));
}

console.log("\n[5] 配对码一次性");
{
  const r = await api("POST", "/api/pairing/claim", { body: { code, deviceName: "重复使用" } });
  check("同码二次兑换失败", r.status === 404, `got ${r.status}`);
  const r2 = await api("POST", "/api/pairing/claim", { body: { code: "000000", deviceName: "瞎猜" } });
  check("错误码兑换失败", r2.status === 404, `got ${r2.status}`);
}

console.log("\n[6] 设备可见性");
{
  const r = await api("GET", "/api/devices", { key: KEY });
  const list = r.json?.devices || [];
  check("监控端能看到设备", list.some((d) => d.id === deviceId), JSON.stringify(list));
  check("初始为离线", list.find((d) => d.id === deviceId)?.online === false);
}

console.log("\n[7] 位置上报");
{
  const r = await api("POST", `/api/devices/${deviceId}/location`, {
    deviceToken,
    body: {
      location: {
        latitude: 29.871207,
        longitude: 115.715476,
        accuracy: 16,
        provider: "gps",
        isApproximate: false,
        timestamp: Date.now(),
      },
    },
  });
  check("上报成功", r.status === 200 && r.json?.ok === true, JSON.stringify(r.json));

  const r2 = await api("POST", `/api/devices/${deviceId}/location`, {
    deviceToken: "bad-token",
    body: { location: { latitude: 1, longitude: 2 } },
  });
  check("错误令牌被拒 401", r2.status === 401, `got ${r2.status}`);

  const r3 = await api("POST", `/api/devices/${deviceId}/location`, {
    deviceToken,
    body: { location: { latitude: "abc" } },
  });
  check("非法坐标被拒 400", r3.status === 400, `got ${r3.status}`);

  const r4 = await api("GET", `/api/devices/${deviceId}`, { key: KEY });
  const loc = r4.json?.device?.lastLocation;
  check("位置已保存", loc?.latitude === 29.871207 && loc?.longitude === 115.715476, JSON.stringify(loc));
}

console.log("\n[8] 照片上传与下载");
{
  // 用一段假 JPEG 数据（FFD8 开头足以验证链路）
  const fakeJpeg = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), Buffer.alloc(2048, 7), Buffer.from([0xff, 0xd9])]);
  const r = await api("POST", `/api/devices/${deviceId}/photos?photoId=p_test1&source=camera&lat=29.87&lon=115.71`, {
    deviceToken,
    raw: fakeJpeg,
  });
  check("上传成功", r.status === 200 && r.json?.photoId === "p_test1", JSON.stringify(r.json));

  const list = await api("GET", `/api/devices/${deviceId}/photos`, { key: KEY });
  const photos = list.json?.photos || [];
  check("照片出现在列表", photos.length === 1 && photos[0].id === "p_test1", JSON.stringify(photos));
  check("记录了拍摄来源", photos[0]?.source === "camera");
  check("记录了坐标", photos[0]?.latitude === 29.87);

  const dl = await fetch(`${BASE}/api/devices/${deviceId}/photos/p_test1`, {
    headers: { Authorization: `Bearer ${KEY}` },
  });
  const buf = Buffer.from(await dl.arrayBuffer());
  check("下载内容一致", buf.length === fakeJpeg.length && buf[0] === 0xff && buf[1] === 0xd8, `len=${buf.length}`);

  const noAuth = await fetch(`${BASE}/api/devices/${deviceId}/photos/p_test1`);
  check("无 Key 无法下载", noAuth.status === 401, `got ${noAuth.status}`);
}

console.log("\n[9] 设备离线时的指令");
{
  const r = await api("POST", `/api/devices/${deviceId}/locate`, { key: KEY });
  check("离线指令明确失败", r.status === 200 && r.json?.ok === false, JSON.stringify(r.json));
}

console.log("\n[10] WebSocket 上线与指令往返");
{
  const ws = new WebSocket(`${BASE.replace("http", "ws")}/ws`);
  const got = [];
  await new Promise((resolve, reject) => {
    ws.on("open", () => ws.send(JSON.stringify({ type: "hello", deviceId, token: deviceToken, name: "测试备用机" })));
    ws.on("message", (raw) => {
      const msg = JSON.parse(raw.toString());
      got.push(msg);
      if (msg.type === "hello_ack") resolve();
      if (msg.type === "command") {
        // 模拟设备执行定位指令
        ws.send(
          JSON.stringify({
            type: "command_result",
            commandId: msg.commandId,
            ok: true,
            location: { latitude: 30.5, longitude: 114.3, accuracy: 8, provider: "gps", timestamp: Date.now() },
          }),
        );
      }
    });
    ws.on("error", reject);
    setTimeout(() => reject(new Error("WS 超时")), 8000);
  });
  check("hello_ack 收到", got.some((m) => m.type === "hello_ack"));

  const online = await api("GET", `/api/devices/${deviceId}`, { key: KEY });
  check("设备显示在线", online.json?.device?.online === true);

  const r = await api("POST", `/api/devices/${deviceId}/locate`, { key: KEY });
  check("在线指令成功", r.status === 200 && r.json?.ok === true, JSON.stringify(r.json));
  check("回传坐标已落库", r.json?.location?.latitude === 30.5, JSON.stringify(r.json?.location));

  ws.close();
  await new Promise((r2) => setTimeout(r2, 400));
}

console.log("\n[11] 设备解绑");
{
  const r = await api("DELETE", `/api/devices/${deviceId}`, { key: KEY });
  check("解绑成功", r.status === 200 && r.json?.ok === true, JSON.stringify(r.json));
  const r2 = await api("GET", `/api/devices/${deviceId}`, { key: KEY });
  check("设备已消失", r2.status === 404, `got ${r2.status}`);
  const r3 = await api("POST", `/api/devices/${deviceId}/location`, {
    deviceToken,
    body: { location: { latitude: 1, longitude: 2 } },
  });
  check("旧令牌失效", r3.status === 404, `got ${r3.status}`);
}

console.log(`\n${failures === 0 ? "✅ 全部通过" : `❌ ${failures} 项失败`}\n`);
process.exit(failures === 0 ? 0 : 1);
