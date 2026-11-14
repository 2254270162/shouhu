import { networkInterfaces } from "node:os";

/**
 * 局域网地址探测。
 *
 * 打包成 exe 后，用户双击运行时最需要知道的是
 * "手机该填哪个地址"，所以启动时把所有可用内网 IP 都打印出来。
 */
export function localAddresses() {
  const result = [];
  const nets = networkInterfaces();
  for (const [name, addrs] of Object.entries(nets)) {
    for (const addr of addrs || []) {
      if (addr.family !== "IPv4" || addr.internal) continue;
      result.push({ iface: name, address: addr.address });
    }
  }
  // 常见家用/办公网段排前面，方便用户一眼找到
  const score = (ip) =>
    ip.startsWith("192.168.") ? 0 : ip.startsWith("10.") ? 1 : ip.startsWith("172.") ? 2 : 3;
  result.sort((a, b) => score(a.address) - score(b.address));
  return result;
}

/** 打印启动信息，包含可直接复制到手机里填的地址。 */
export function printStartupBanner(port, addresses) {
  const line = "─".repeat(58);
  console.log("");
  console.log(line);
  console.log("  设备守护服务端已启动");
  console.log(line);
  console.log(`  本机访问   http://127.0.0.1:${port}`);
  if (addresses.length === 0) {
    console.log("  局域网     未检测到内网 IP，请确认已连接 WiFi 或网线");
  } else {
    console.log("  局域网访问（手机连同一个 WiFi 后用这些地址）：");
    for (const a of addresses) {
      console.log(`             http://${a.address}:${port}    [${a.iface}]`);
    }
  }
  console.log("");
  console.log("  在手机浏览器打开上面的地址即可管理设备。");
  console.log("  App 里的「服务器地址」也填同一个。");
  console.log(line);
  console.log("");
}
