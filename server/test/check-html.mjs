// 提取 index.html 里的内联脚本做语法检查，防止部署后才暴露空白页
import { readFileSync, writeFileSync } from "node:fs";
import { execSync } from "node:child_process";

const html = readFileSync("E:/AI/APP/server/public/index.html", "utf8");
const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map((m) => m[1]);
if (!scripts.length) {
  console.log("没有内联脚本");
  process.exit(1);
}

let failed = 0;
scripts.forEach((code, i) => {
  const tmp = `E:/AI/APP/.toolchain/_syntax_${i}.mjs`;
  writeFileSync(tmp, code);
  try {
    execSync(`node --check "${tmp}"`, { stdio: "pipe" });
    console.log(`✅ 脚本 #${i} 语法正确 (${code.length} 字符)`);
  } catch (e) {
    failed++;
    console.log(`❌ 脚本 #${i} 语法错误:`);
    console.log(e.stderr?.toString() || e.message);
  }
});

process.exit(failed ? 1 : 0);
