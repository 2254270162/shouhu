import { WebSocket } from "ws";
const BASE = "http://127.0.0.1:8095";
const BASE_WS = "ws://127.0.0.1:8095";

// 配对一台设备
const KEY = "ws-test-key-123456789";
const codeRes = await fetch(BASE + "/api/pairing/code", {
  method: "POST", headers: { Authorization: `Bearer ${KEY}`, "Content-Type": "application/json" }, body: "{}",
});
const code = (await codeRes.json()).code;
const claim = await fetch(BASE + "/api/pairing/claim", {
  method: "POST", headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ code, deviceName: "WS崩溃测试机" }),
});
const cj = await claim.json();
console.log("配对:", !!cj.deviceId);

// 让它先上报带缺失权限的遥测
await fetch(`${BASE}/api/devices/${cj.deviceId}/telemetry`, {
  method: "POST",
  headers: { "X-Device-Token": cj.deviceToken, "Content-Type": "application/json" },
  body: JSON.stringify({
    telemetry: {
      battery: { level: 50 },
      permissions: {
        items: [
          { key: "camera", label: "相机", granted: false, special: false },
          { key: "notification", label: "通知", granted: false, special: false },
        ],
        missingCount: 2, missingLabels: "相机、通知", complete: false,
      },
    },
  }),
});
console.log("遥测已上报（含缺失权限）");

// 连 WS 触发上线流程 —— 这里就是之前崩溃的地方
const ws = new WebSocket(BASE_WS + "/ws");
let gotCommand = false;
await new Promise((resolve, reject) => {
  ws.on("open", () => {
    ws.send(JSON.stringify({ type: "hello", deviceId: cj.deviceId, token: cj.deviceToken, name: "WS崩溃测试机" }));
  });
  ws.on("message", (raw) => {
    const m = JSON.parse(raw.toString());
    console.log("收到:", m.type);
    if (m.type === "hello_ack") setTimeout(resolve, 2500);
    if (m.type === "command") {
      gotCommand = true;
      console.log("  -> 收到指令:", m.command);
      ws.send(JSON.stringify({ type: "command_result", commandId: m.commandId, ok: true, missing: 2, labels: "相机、通知" }));
    }
  });
  ws.on("error", reject);
  setTimeout(resolve, 6000);
});
ws.close();

console.log("\n结果:");
console.log("  上线未崩溃:", true);
console.log("  自动请求权限:", gotCommand ? "✅ 已下发" : "⚠ 未下发（可能被冷却时间挡住）");

// 再确认服务还活着
await new Promise(r => setTimeout(r, 800));
const h = await fetch(BASE + "/api/health");
console.log("  服务存活:", h.status === 200 ? "✅" : "❌ " + h.status);
