import { createWriteStream, existsSync, mkdirSync, readdirSync, rmSync, statSync } from "node:fs";
import { join, basename } from "node:path";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { extractAll, hasBundledResources } from "./resources.js";

const execFileAsync = promisify(execFile);

/**
 * 构建环境安装。
 *
 * 两种来源，优先用内置资源：
 *
 * 1. **内置资源**（推荐，局域网可用）
 *    exe 尾部附加了 JDK + Gradle + SDK + 工程源码 + 依赖缓存，
 *    安装时直接释放到磁盘，不依赖网络。
 *
 * 2. **在线下载**（备用）
 *    没有内置资源时（比如开发环境），走网络下载。
 *    局域网部署基本用不到这条路径。
 *
 * 为什么资源不放进 SEA assets：SEA 会把资源整体读进内存，
 * 2GB 会直接 OOM。所以用「exe 尾部附加归档 + 按需释放」。
 */

export const INSTALL_STEPS = [
  { key: "resources", label: "释放内置资源", size: "" },
  { key: "verify", label: "校验环境", size: "" },
];

export class SetupManager {
  constructor(buildRoot, options = {}) {
    this.buildRoot = buildRoot;
    this.exePath = options.exePath || process.execPath;
    this.onLog = options.onLog || (() => {});
    this.state = {
      running: false,
      step: null,
      stepIndex: 0,
      totalSteps: INSTALL_STEPS.length,
      downloaded: 0,
      total: 0,
      message: "未开始",
      error: null,
    };
  }

  /** 内置资源是否可用。 */
  hasBundled() {
    return hasBundledResources(this.exePath);
  }

  /** 当前安装状态，供前端轮询。 */
  status() {
    return {
      ...this.state,
      hasBundled: this.hasBundled(),
      exePath: this.exePath,
      buildRoot: this.buildRoot,
      steps: INSTALL_STEPS.map((s, i) => ({
        ...s,
        done: i < this.state.stepIndex,
        current: i === this.state.stepIndex && this.state.running,
      })),
    };
  }

  isDone(key) {
    return existsSync(join(this.buildRoot, `.installed-${key}`));
  }

  markDone(key) {
    try {
      mkdirSync(this.buildRoot, { recursive: true });
      createWriteStream(join(this.buildRoot, `.installed-${key}`)).end();
    } catch {
      /* 忽略 */
    }
  }

  progress() {
    const done = INSTALL_STEPS.filter((s) => this.isDone(s.key)).length;
    return { done, total: INSTALL_STEPS.length, complete: done === INSTALL_STEPS.length };
  }

  /**
   * 执行安装。
   *
   * @param {object} opts { force: 强制重新释放 }
   */
  async install(opts = {}) {
    if (this.state.running) throw new Error("安装已在进行中");

    this.state = { ...this.state, running: true, error: null, stepIndex: 0, message: "开始安装" };

    try {
      mkdirSync(this.buildRoot, { recursive: true });

      // 第一步：释放内置资源
      this.state.step = "resources";
      this.state.message = "正在释放内置资源…";

      if (this.hasBundled()) {
        this.log("从 exe 内置资源释放构建环境…");
        const count = extractAll(this.exePath, this.buildRoot, (m) => this.log(m));
        this.log(`已释放 ${count} 个归档`);
      } else if (existsSync(join(this.buildRoot, "gradle-8.7"))) {
        this.log("未内置资源，但检测到已有构建环境，跳过释放");
      } else {
        throw new Error(
          "当前 exe 未包含构建环境资源。\n" +
            "请使用带资源的完整版 exe，或手动把 JDK/Gradle/SDK 放到 " +
            this.buildRoot,
        );
      }

      this.markDone("resources");
      this.state.stepIndex = 1;

      // 第二步：校验关键路径
      this.state.step = "verify";
      this.state.message = "正在校验环境…";
      const checks = [
        ["JDK", join(this.buildRoot, "jdk")],
        ["Gradle", join(this.buildRoot, "gradle-8.7")],
        ["Android SDK", join(this.buildRoot, "sdk", "platforms")],
        ["工程源码", join(this.buildRoot, "workspace", "settings.gradle.kts")],
      ];
      const missing = checks.filter(([, p]) => !existsSync(p)).map(([n]) => n);
      if (missing.length > 0) {
        throw new Error(`缺少组件：${missing.join("、")}`);
      }
      this.log("环境校验通过");
      this.markDone("verify");
      this.state.stepIndex = 2;

      this.state.running = false;
      this.state.step = null;
      this.state.message = "安装完成，可以编译 APK 了";
      this.log("全部完成");
      return this.status();
    } catch (e) {
      this.state.running = false;
      this.state.error = e.message;
      this.state.message = `安装失败：${e.message}`;
      this.log(`安装失败：${e.message}`);
      throw e;
    }
  }

  log(msg) {
    this.onLog(`[setup] ${msg}`);
  }
}
