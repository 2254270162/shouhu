// 品牌定制测试：应用与还原是否干净
import { applyBranding, currentBranding, validateAppName, validatePackageName, saveIcon, removeIcon } from "/data/home/guardian/src/branding.js";
import { readFileSync, existsSync, writeFileSync } from "node:fs";

const WS = "/data/home/android-build/workspace";
const stringsPath = `${WS}/app/src/main/res/values/strings.xml`;
const gradlePath = `${WS}/app/build.gradle.kts`;
const iconXml = `${WS}/app/src/main/res/drawable/ic_launcher.xml`;

let failures = 0;
const ok = (n, c, e = "") => {
  console.log(`  ${c ? "✅" : "❌"} ${n}${c ? "" : " " + e}`);
  if (!c) failures++;
};

console.log("=== 记录原始状态 ===");
const origStrings = readFileSync(stringsPath, "utf8");
const origGradle = readFileSync(gradlePath, "utf8");
const origIcon = readFileSync(iconXml, "utf8");
console.log("已记录 strings / gradle / icon 原文");

console.log("\n[1] 名称与包名校验");
{
  ok("正常名称通过", validateAppName("巡检助手") === "巡检助手");
  try {
    validateAppName("");
    ok("空名称被拒", false);
  } catch {
    ok("空名称被拒", true);
  }
  try {
    validateAppName("这是一个非常非常长的应用名称超过限制");
    ok("超长名称被拒", false);
  } catch {
    ok("超长名称被拒", true);
  }

  ok("正常包名通过", validatePackageName("com.acme.inspect") === "com.acme.inspect");
  try {
    validatePackageName("Invalid-Package");
    ok("非法包名被拒", false);
  } catch {
    ok("非法包名被拒", true);
  }
  try {
    validatePackageName("nodot");
    ok("单段包名被拒", false);
  } catch {
    ok("单段包名被拒", true);
  }
}

console.log("\n[2] 应用品牌配置");
let restore;
{
  restore = applyBranding({
    appName: "巡检助手",
    packageName: "com.acme.inspect",
  });

  const strings = readFileSync(stringsPath, "utf8");
  ok("名称已写入", strings.includes("巡检助手"), strings.trim());

  const gradle = readFileSync(gradlePath, "utf8");
  ok("applicationId 已改", gradle.includes('applicationId = "com.acme.inspect"'));
  ok("namespace 已改", gradle.includes('namespace = "com.acme.inspect"'));
}

console.log("\n[3] 还原");
{
  restore();

  const strings = readFileSync(stringsPath, "utf8");
  ok("名称已还原", strings === origStrings);

  const gradle = readFileSync(gradlePath, "utf8");
  ok("gradle 已还原", gradle === origGradle);

  const icon = readFileSync(iconXml, "utf8");
  ok("图标已还原", icon === origIcon);
}

console.log("\n[4] 异常时也能还原");
{
  let restore2;
  try {
    restore2 = applyBranding({ appName: "临时名称" });
    // 模拟中途抛错
    throw new Error("模拟构建失败");
  } catch {
    // 正常情况下调用方会在 finally 里还原
    if (restore2) restore2();
  }
  const strings = readFileSync(stringsPath, "utf8");
  ok("失败后已还原", strings === origStrings, strings.trim());
}

console.log("\n[5] 图标上传与清除");
{
  // 构造一个最小 PNG 头
  const fakePng = Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    Buffer.alloc(100, 0),
  ]);
  saveIcon(fakePng, "icon.png");
  ok("图标已保存", currentBranding().hasCustomIcon);

  removeIcon();
  ok("图标已删除", !currentBranding().hasCustomIcon);
}

console.log(`\n${failures === 0 ? "✅ 全部通过" : `❌ ${failures} 项失败`}\n`);
process.exit(failures === 0 ? 0 : 1);
