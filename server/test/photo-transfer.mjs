// 真实图片传输验证：构造一张真 JPEG，走完整上传/下载链路，校验字节一致性
import { WebSocket } from "ws";

const BASE = process.env.BASE || "http://127.0.0.1:8080";
const KEY = "monitor-key-1234567890";

function ok(name, cond, extra = "") {
  console.log(`  ${cond ? "✅" : "❌"} ${name}${cond ? "" : " " + extra}`);
  if (!cond) process.exitCode = 1;
}

async function api(method, path, { key, deviceToken, body, raw, headers = {} } = {}) {
  const h = { ...headers };
  if (key) h["Authorization"] = `Bearer ${key}`;
  if (deviceToken) h["X-Device-Token"] = deviceToken;
  if (body) h["Content-Type"] = "application/json";
  const res = await fetch(BASE + path, { method, headers: h, body: body ? JSON.stringify(body) : raw });
  const buf = Buffer.from(await res.arrayBuffer());
  let json = null;
  try {
    json = JSON.parse(buf.toString("utf8"));
  } catch {
    /* 二进制 */
  }
  return { status: res.status, json, buf, contentType: res.headers.get("content-type") };
}

/** 生成一张可被解码的真 JPEG（用最小合法 JPEG 结构 + 注释段塞入可控数据） */
function makeJpeg(payloadBytes) {
  const parts = [];
  parts.push(Buffer.from([0xff, 0xd8])); // SOI
  // APP15 段承载随机数据，模拟真实照片体积
  const chunkSize = 60000;
  for (let i = 0; i < payloadBytes.length; i += chunkSize) {
    const chunk = payloadBytes.subarray(i, i + chunkSize);
    const len = chunk.length + 2;
    parts.push(Buffer.from([0xff, 0xef, len >> 8, len & 0xff]));
    parts.push(chunk);
  }
  parts.push(Buffer.from([0xff, 0xd9])); // EOI
  return Buffer.concat(parts);
}

console.log("\n[准备] 注册 Key 并配对一台设备");
const codeRes = await api("POST", "/api/pairing/code", { key: KEY, body: {} });
const code = codeRes.json?.code;
ok("拿到配对码", /^\d{6}$/.test(code || ""));

const claim = await api("POST", "/api/pairing/claim", {
  body: { code, deviceName: "传输测试机", model: "Test", appVersion: "1.0" },
});
const deviceId = claim.json?.deviceId;
const deviceToken = claim.json?.deviceToken;
ok("设备兑换成功", !!deviceId && !!deviceToken);

console.log("\n[1] 上传 1.2MB 模拟照片（纯二进制 body，非 base64）");
const payload = Buffer.alloc(1_200_000);
for (let i = 0; i < payload.length; i++) payload[i] = (i * 7 + 13) & 0xff;
const jpeg = makeJpeg(payload);
const up = await api("POST", `/api/devices/${deviceId}/photos?photoId=p_real1&source=camera&lat=29.871207&lon=115.715476&takenAt=1794382000000`, {
  deviceToken,
  raw: jpeg,
  headers: { "Content-Type": "image/jpeg" },
});
ok("上传成功", up.status === 200 && up.json?.photoId === "p_real1", JSON.stringify(up.json));

console.log("\n[2] 元数据校验");
const list = await api("GET", `/api/devices/${deviceId}/photos`, { key: KEY });
const meta = list.json?.photos?.find((p) => p.id === "p_real1");
ok("出现在照片列表", !!meta);
ok("来源为 camera", meta?.source === "camera");
ok("记录纬度", meta?.latitude === 29.871207);
ok("记录经度", meta?.longitude === 115.715476);
ok("记录文件大小", meta?.size === jpeg.length, `meta=${meta?.size} real=${jpeg.length}`);
ok("记录 sha256", /^[0-9a-f]{64}$/.test(meta?.sha256 || ""));

console.log("\n[3] 下载并逐字节比对");
const dl = await api("GET", `/api/devices/${deviceId}/photos/p_real1`, { key: KEY });
ok("下载成功", dl.status === 200);
ok("Content-Type 是 image/jpeg", (dl.contentType || "").includes("image/jpeg"), dl.contentType);
ok("字节数一致", dl.buf.length === jpeg.length, `got=${dl.buf.length} want=${jpeg.length}`);
ok("内容完全一致", dl.buf.equals(jpeg));
ok("JPEG 头正确", dl.buf[0] === 0xff && dl.buf[1] === 0xd8);
ok("JPEG 尾正确", dl.buf[dl.buf.length - 2] === 0xff && dl.buf[dl.buf.length - 1] === 0xd9);

console.log("\n[4] 鉴权隔离");
const noKey = await api("GET", `/api/devices/${deviceId}/photos/p_real1`);
ok("无 Key 无法下载", noKey.status === 401, `got ${noKey.status}`);
const otherKey = await api("GET", `/api/devices/${deviceId}/photos/p_real1`, { key: "another-monitor-key-999" });
ok("别的 Key 无法下载", otherKey.status === 401 || otherKey.status === 404, `got ${otherKey.status}`);

console.log("\n[5] 超限保护");
const huge = Buffer.alloc(21 * 1024 * 1024, 1);
const tooBig = await api("POST", `/api/devices/${deviceId}/photos?photoId=p_huge`, {
  deviceToken,
  raw: huge,
  headers: { "Content-Type": "image/jpeg" },
}).catch((e) => ({ status: -1, json: { message: e.message } }));
ok("超过 20MB 被拒", tooBig.status !== 200, `got ${tooBig.status}`);

console.log("\n[6] WebSocket 拍照指令往返（设备上传后回传 photoId）");
{
  const ws = new WebSocket(`${BASE.replace("http", "ws").replace("https", "wss")}/ws`);
  await new Promise((resolve, reject) => {
    ws.on("open", () => ws.send(JSON.stringify({ type: "hello", deviceId, token: deviceToken, name: "传输测试机" })));
    ws.on("message", async (raw) => {
      const msg = JSON.parse(raw.toString());
      if (msg.type === "hello_ack") resolve();
      if (msg.type === "command" && msg.command === "capture") {
        // 模拟设备：拍完直接上传，再把 photoId 回给服务端
        const photoId = `cap_${Date.now()}`;
        await api("POST", `/api/devices/${deviceId}/photos?photoId=${photoId}&source=camera`, {
          deviceToken,
          raw: jpeg,
          headers: { "Content-Type": "image/jpeg" },
        });
        ws.send(JSON.stringify({ type: "command_result", commandId: msg.commandId, ok: true, data: { photoId } }));
      }
    });
    ws.on("error", reject);
    setTimeout(() => reject(new Error("WS 超时")), 8000);
  });

  const cap = await api("POST", `/api/devices/${deviceId}/capture`, { key: KEY, body: { lens: "back" } });
  ok("指令返回成功", cap.json?.ok === true, JSON.stringify(cap.json));
  ok("回传了 photoId", !!cap.json?.data?.photoId, JSON.stringify(cap.json?.data));

  const photoId = cap.json?.data?.photoId;
  if (photoId) {
    const fetched = await api("GET", `/api/devices/${deviceId}/photos/${photoId}`, { key: KEY });
    ok("监控端能取到该照片", fetched.status === 200 && fetched.buf.equals(jpeg), `status=${fetched.status}`);
  }
  ws.close();
  await new Promise((r) => setTimeout(r, 300));
}

console.log(`\n${process.exitCode ? "❌ 存在失败项" : "✅ 图片传输全部通过"}\n`);
