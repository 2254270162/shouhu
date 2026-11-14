import { execFile } from "node:child_process";
import {
  existsSync,
  mkdirSync,
  readdirSync,
  statSync,
  copyFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { dirname, join } from "node:path";
import { promisify } from "node:util";
import { applyBranding } from "./branding.js";

const execFileAsync = promisify(execFile);

/**
 * 服务端 APK 构建。
 *
 * 用途：几十台 PDA 需要装 App，不该每次都在本地编译再手动上传。
 * 在服务端直接构建，构建产物自动进「安装包分发」，PDA 刷新安装页就能拿到新包。
 *
 * 工作方式：
 * 1. 把 android-src/ 下的工程同步到构建目录；
 * 2. 调用服务器上的 Gradle 编译 debug APK；
 * 3. 把产物复制到 apk 目录，改名带时间戳，便于区分版本。
 *
 * 安全约束：
 * - 构建目录固定在 BUILD_ROOT，不接受路径参数，避免任意路径写入；
 * - 只允许构建预设的模块（app / monitor），不接受任意 gradle task；
 * - 同一时间只允许一个构建在跑，避免 4 核机器被并发拖垮。
 */

/*
 * 构建根目录。
 *
 * 默认值分两种情况：
 * - 开发环境（直接跑 node src/index.js）：用 Linux 上的固定路径；
 * - 打包成 exe 后：放在 exe 同级的 android-build 目录，
 *   因为用户的机器上没有 /data/home 这种路径，
 *   而且构建环境是由 exe 内的资源释放出来的，就该跟 exe 放在一起。
 */
function resolveBuildRoot() {
  if (process.env.BUILD_ROOT) return process.env.BUILD_ROOT;

  // SEA 运行时 process.execPath 指向 exe 本身
  const isSea = typeof process.getBuiltinModule === "function" || process.execPath.endsWith(".exe");
  if (isSea) {
    return join(dirname(process.execPath), "android-build");
  }

  return "/data/home/android-build";
}

const BUILD_ROOT = resolveBuildRoot();
const WORKSPACE = join(BUILD_ROOT, "workspace");
const GRADLE_BIN = join(BUILD_ROOT, "gradle-8.7", "bin", "gradle");
const SDK_ROOT = join(BUILD_ROOT, "sdk");
const APK_OUT = process.env.APK_DIR || join(process.env.DATA_DIR || join(process.cwd(), "data"), "apk");

/** 构建锁文件：防止并发构建，也能在进程重启后识别残留锁。 */
const LOCK_FILE = join(BUILD_ROOT, ".building");

/** 允许构建的模块。键是接口里用的名字，值是 Gradle 模块路径。 */
export const MODULES = {
  device: { task: ":app:assembleDebug", apk: "app/build/outputs/apk/debug/app-debug.apk", label: "被监控端" },
  monitor: { task: ":monitor:assembleDebug", apk: "monitor/build/outputs/apk/debug/monitor-debug.apk", label: "监控端" },
};

/** 当前构建状态，供接口轮询。 */
let current = {
  running: false,
  module: null,
  startedAt: 0,
  finishedAt: 0,
  ok: null,
  log: "",
  output: null,
  error: null,
};

export function buildStatus() {
  return { ...current };
}

/** 环境是否就绪（SDK 与 Gradle 都装好了）。 */
export function envReady() {
  // JDK 也要检查：exe 版是自己释放的 JDK，不能用系统的
  return (
    existsSync(join(BUILD_ROOT, "jdk")) &&
    existsSync(GRADLE_BIN) &&
    existsSync(join(SDK_ROOT, "platforms")) &&
    existsSync(WORKSPACE)
  );
}

export function envInfo() {
  return {
    buildRoot: BUILD_ROOT,
    jdk: existsSync(join(BUILD_ROOT, "jdk")),
    gradle: existsSync(GRADLE_BIN),
    sdk: existsSync(join(SDK_ROOT, "platforms")),
    workspace: existsSync(WORKSPACE),
    gradleHome: existsSync(join(BUILD_ROOT, "gradle-home")),
    ready: envReady(),
  };
}

/**
 * 触发一次构建。
 *
 * 不用 await 等构建结束（APK 构建要 1–3 分钟，HTTP 会超时），
 * 立即返回，由前端轮询 /api/admin/build/status。
 */
export function startBuild(moduleKey, branding = null) {
  const mod = MODULES[moduleKey];
  if (!mod) throw new Error(`不支持的模块：${moduleKey}，可选 ${Object.keys(MODULES).join(" / ")}`);
  if (!envReady()) throw new Error("构建环境未就绪，请先安装 Android SDK 与 Gradle");
  /*
   * 品牌定制（改名/改图标/改包名）只对被监控端有意义。
   * 但监控端也需要把服务器地址写进 APK，方便登录页预填，
   * 所以这里不再一律拒绝，而是按字段区分处理（见 branding.js）。
   */

  // 先用文件锁判断：内存状态在进程重启后会丢，锁文件能跨进程拦住并发构建
  if (existsSync(LOCK_FILE)) {
    const age = Date.now() - statSync(LOCK_FILE).mtimeMs;
    // 超过 15 分钟视为残留锁（进程被强杀时留下的），自动清理
    if (age < 15 * 60 * 1000) {
      throw new Error(`已有构建在进行中（已持续 ${Math.floor(age / 1000)} 秒），请等待完成`);
    }
    try {
      rmSync(LOCK_FILE, { force: true });
    } catch {
      /* 清理失败则继续尝试构建 */
    }
  }
  if (current.running) throw new Error("已有构建在进行中，请等待完成");

  // 加锁，避免并发构建把 4 核机器拖垮
  try {
    writeFileSync(LOCK_FILE, JSON.stringify({ moduleKey, startedAt: Date.now() }), "utf8");
  } catch (e) {
    throw new Error(`无法创建构建锁：${e.message}`);
  }

  current = {
    running: true,
    module: moduleKey,
    startedAt: Date.now(),
    finishedAt: 0,
    ok: null,
    log: "",
    output: null,
    error: null,
    branding: branding || null,
  };

  runBuild(moduleKey, mod, branding)
    .catch((e) => {
      current.running = false;
      current.ok = false;
      current.finishedAt = Date.now();
      current.error = e.message;
    })
    .finally(() => {
      try {
        rmSync(LOCK_FILE, { force: true });
      } catch {
        /* 忽略 */
      }
    });

  return buildStatus();
}

async function runBuild(moduleKey, mod, branding) {
  if (!existsSync(WORKSPACE)) throw new Error(`工程目录不存在：${WORKSPACE}`);

  mkdirSync(APK_OUT, { recursive: true });

  // 应用品牌定制。构建结束无论成败都还原，否则下次构建会把上次的配置带进来。
  let restoreBranding = null;
  if (branding) {
    try {
      restoreBranding = applyBranding(branding);
    } catch (e) {
      throw new Error(`应用品牌配置失败：${e.message}`);
    }
  }

  const env = {
    ...process.env,
    ANDROID_HOME: SDK_ROOT,
    ANDROID_SDK_ROOT: SDK_ROOT,
    /*
     * JDK 优先用构建目录里的那份。
     *
     * exe 版会释放自带 JDK，用它才能保证版本一致；
     * 开发环境没有释放过，回落到系统的 JAVA_HOME。
     */
    JAVA_HOME: existsSync(join(BUILD_ROOT, "jdk"))
      ? join(BUILD_ROOT, "jdk")
      : process.env.JAVA_HOME || "/usr/lib/jvm/java-21-openjdk-amd64",
    GRADLE_USER_HOME: join(BUILD_ROOT, "gradle-home"),
  };

  try {
    // 首次构建要下载依赖，给它 10 分钟
    const { stdout, stderr } = await execFileAsync(
      GRADLE_BIN,
      [mod.task, "--no-daemon", "--console=plain"],
      { cwd: WORKSPACE, env, timeout: 10 * 60 * 1000, maxBuffer: 20 * 1024 * 1024 },
    ).catch((e) => {
      // 构建失败时把日志留下来，方便定位
      current.log = `${e.stdout || ""}\n${e.stderr || ""}`.slice(-8000);
      throw new Error("构建失败，详见日志");
    });

    current.log = `${stdout || ""}\n${stderr || ""}`.slice(-8000);

    // 找到产物
    const apkPath = join(WORKSPACE, mod.apk);
    if (!existsSync(apkPath)) throw new Error(`构建成功但找不到产物：${mod.apk}`);

    // 复制到分发目录，文件名带品牌与时间戳
    const ts = new Date().toISOString().replace(/[-:T]/g, "").slice(0, 14);
    const brandLabel = branding?.appName ? sanitizeForFileName(branding.appName) : mod.label;
    const outName = `${brandLabel}-${ts}.apk`;
    copyFileSync(apkPath, join(APK_OUT, outName));

    const size = statSync(join(APK_OUT, outName)).size;

    current.running = false;
    current.ok = true;
    current.finishedAt = Date.now();
    current.output = {
      name: outName,
      size,
      appName: branding?.appName || mod.label,
      packageName: branding?.packageName || null,
    };

    // 清理旧版本，只保留最近 5 个，避免磁盘被占满
    pruneOldBuilds(brandLabel, 5);
  } finally {
    if (restoreBranding) {
      try {
        restoreBranding();
      } catch (e) {
        console.warn("[build] 还原品牌配置失败:", e.message);
      }
    }
  }
}

/** 文件名里去掉不安全字符。 */
function sanitizeForFileName(s) {
  return String(s).replace(/[\\/:*?"<>|]/g, "_").slice(0, 40) || "app";
}

/** 只保留最近 N 个某模块的构建产物。 */
function pruneOldBuilds(label, keep) {
  try {
    const files = readdirSync(APK_OUT)
      .filter((f) => f.startsWith(label) && f.endsWith(".apk"))
      .map((f) => ({ f, t: statSync(join(APK_OUT, f)).mtimeMs }))
      .sort((a, b) => b.t - a.t);

    for (const item of files.slice(keep)) {
      removeIfInside(APK_OUT, join(APK_OUT, item.f));
    }
  } catch {
    /* 清理失败不影响构建结果 */
  }
}

function removeIfInside(root, target) {
  if (!target.startsWith(root)) return;
  try {
    rmSync(target, { force: true });
  } catch {
    /* 忽略 */
  }
}
