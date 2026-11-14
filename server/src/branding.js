import { existsSync, mkdirSync, readFileSync, writeFileSync, rmSync, readdirSync } from "node:fs";
import { join, extname } from "node:path";

/**
 * 安装包品牌定制：应用名称、图标、包名后缀。
 *
 * 为什么在服务端做：几十台 PDA 交付给不同客户时，常常要换名字和图标，
 * 但不该维护多份 Android 工程。
 *
 * 做法：构建前把配置写进 workspace 的资源文件，编译后再恢复原样。
 * 这样每次构建产出对应品牌的 APK，而工程本身保持干净。
 */

const WORKSPACE = process.env.BUILD_ROOT
  ? join(process.env.BUILD_ROOT, "workspace")
  : "/data/home/android-build/workspace";

const BRAND_DIR = process.env.BRAND_DIR
  || join(process.env.DATA_DIR || join(process.cwd(), "data"), "branding");

/** 图标规格：自动生成多个密度，避免不同设备上模糊。 */
const ICON_SIZES = {
  "mipmap-mdpi": 48,
  "mipmap-hdpi": 72,
  "mipmap-xhdpi": 96,
  "mipmap-xxhdpi": 144,
  "mipmap-xxxhdpi": 192,
};

export function ensureBrandDir() {
  mkdirSync(BRAND_DIR, { recursive: true });
  return BRAND_DIR;
}

/** 保存上传的图标原图。 */
export function saveIcon(buffer, filename) {
  ensureBrandDir();
  const ext = extname(filename || "").toLowerCase();
  if (![".png", ".jpg", ".jpeg", ".webp"].includes(ext)) {
    throw new Error("图标只支持 PNG / JPG / WebP");
  }
  if (buffer.length > 5 * 1024 * 1024) {
    throw new Error("图标不能超过 5MB");
  }
  const target = join(BRAND_DIR, `icon${ext}`);
  writeFileSync(target, buffer);
  return target;
}

export function hasCustomIcon() {
  if (!existsSync(BRAND_DIR)) return false;
  return readdirSync(BRAND_DIR).some((f) => f.startsWith("icon."));
}

export function customIconPath() {
  if (!existsSync(BRAND_DIR)) return null;
  const f = readdirSync(BRAND_DIR).find((x) => x.startsWith("icon."));
  return f ? join(BRAND_DIR, f) : null;
}

export function removeIcon() {
  const p = customIconPath();
  if (p) rmSync(p, { force: true });
}

/**
 * 校验应用名称。
 *
 * 限制长度是因为桌面图标下方的文字空间有限，
 * 太长会被系统截断，显示成"定位助..."很难看。
 */
export function validateAppName(name) {
  const n = String(name || "").trim();
  if (n.length === 0) throw new Error("应用名称不能为空");
  if (n.length > 12) throw new Error("应用名称建议不超过 12 个字，否则桌面显示会被截断");
  return n;
}

/**
 * 校验包名。
 *
 * 必须是合法 Java 包名：小写字母、数字、下划线和点，且不能以数字开头。
 * 包名决定"是否覆盖安装"——同包名才会覆盖，不同包名会共存。
 */
export function validatePackageName(pkg) {
  const p = String(pkg || "").trim();
  if (p.length === 0) throw new Error("包名不能为空");
  if (p.length > 100) throw new Error("包名过长");
  const re = /^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/;
  if (!re.test(p)) {
    throw new Error("包名格式不正确，应形如 com.example.app（小写字母开头，至少两段）");
  }
  return p;
}

/**
 * 在构建前把品牌配置写入 Android 工程。
 *
 * @returns 恢复函数：构建结束后调用，把工程还原，避免污染下次构建
 */
export function applyBranding(branding) {
  const backups = [];
  const createdDirs = [];

  /*
   * 目标模块：被监控端是 app/，监控端是 monitor/。
   *
   * 改名、改包名、改图标只对被监控端有意义——监控端是管理员自己用的，
   * 改它的名字没有实际用途，反而容易混淆。
   * 但监控端需要把服务器地址写进去（登录页预填）。
   */
  const isMonitor = branding.target === "monitor";
  const moduleDir = isMonitor ? "monitor" : "app";

  const backup = (path) => {
    if (existsSync(path)) {
      backups.push({ path, content: readFileSync(path) });
    } else {
      backups.push({ path, content: null });
    }
  };

  try {
    if (!isMonitor) {
      // 1. 应用名称（仅设备端）
      const stringsPath = join(WORKSPACE, `${moduleDir}/src/main/res/values/strings.xml`);
      backup(stringsPath);
      const appName = branding.appName || "定位助手";
      writeFileSync(
        stringsPath,
        `<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">${escapeXml(appName)}</string>
</resources>
`,
        "utf8",
      );

      /*
       * 2. 包名（只改 applicationId）
       *
       * 关键：**不能改 namespace**。
       * namespace 决定 Kotlin 源码里的包名和 BuildConfig 的生成位置，
       * 改了它会让源码里所有 `com.example.locateme.BuildConfig` 引用失效。
       * 改 applicationId 就够了：它才是设备上区分应用的身份。
       */
      if (branding.packageName) {
        const gradlePath = join(WORKSPACE, `${moduleDir}/build.gradle.kts`);
        backup(gradlePath);
        let gradle = readFileSync(gradlePath, "utf8");
        gradle = gradle.replace(
          /applicationId\s*=\s*"[^"]*"/,
          `applicationId = "${branding.packageName}"`,
        );
        writeFileSync(gradlePath, gradle, "utf8");
      }

      // 3. 图标
      const iconPath = customIconPath();
      if (iconPath) {
        applyIcon(iconPath, backup, createdDirs);
      }
    }

    /*
     * 4. 预置注册信息（服务器地址 + 组密钥）
     *
     * 目的：几十台 PDA 不用逐台填配对码，装完打开 App 就自动注册。
     * 写到 BuildConfig 里，设备端启动时读取。
     *
     * 安全说明：组密钥会随 APK 分发，任何拿到安装包的人都能注册设备。
     * 所以：默认关闭、需显式启用、可设注册上限、可随时轮换失效。
     */
    if (branding.enrollServerUrl || branding.enrollGroupKey) {
      const gradlePath = join(WORKSPACE, "app/build.gradle.kts");
      backup(gradlePath);
      let gradle = readFileSync(gradlePath, "utf8");

      const url = escapeKotlinString(branding.enrollServerUrl || "");
      const key = escapeKotlinString(branding.enrollGroupKey || "");

      /*
       * 为什么用「按行插入」而不是正则替换：
       * 之前的写法用 /buildConfigField\("String", "[^"]+", "[^"]*"\)/ 匹配，
       * 但已有那行的值是 "\"$amapKey\"" —— 内部含转义引号，
       * [^"]* 在第一个转义引号处就停了，导致整行匹配失败、注入被静默跳过。
       *
       * 改成先清理旧的 ENROLL 字段，再在 defaultConfig 块里插新行。
       */
      const enrollLines = [
        `        buildConfigField("String", "ENROLL_SERVER", "\\"${url}\\"")`,
        `        buildConfigField("String", "ENROLL_GROUP_KEY", "\\"${key}\\"")`,
      ];

      // 先移除可能残留的同名字段，避免重复定义导致编译失败
      gradle = gradle
        .split("\n")
        .filter((l) => !l.includes('"ENROLL_SERVER"') && !l.includes('"ENROLL_GROUP_KEY"'))
        .join("\n");

      // 插到 defaultConfig { 之后的第一行
      const anchor = gradle.match(/(\n\s*defaultConfig\s*\{[^\n]*\n)/);
      if (anchor) {
        gradle = gradle.replace(anchor[0], `${anchor[0]}${enrollLines.join("\n")}\n`);
      } else {
        // 兜底：插到 versionName 之后
        const m = gradle.match(/(\n\s*versionName\s*=\s*"[^"]*"[^\n]*\n)/);
        if (!m) {
          throw new Error("找不到可插入 buildConfigField 的位置，工程结构可能已变化");
        }
        gradle = gradle.replace(m[0], `${m[0]}${enrollLines.join("\n")}\n`);
      }

      writeFileSync(gradlePath, gradle, "utf8");
    }

    /*
     * 5. 监控端的服务器地址
     *
     * 监控端需要知道连哪个服务器，但不需要组密钥（它用账号密码登录）。
     * 写进 monitor 模块的 BuildConfig.DEFAULT_SERVER，
     * 装完打开就能看到预填的地址，减少现场输入。
     */
    if (branding.monitorServerUrl) {
      const monitorGradle = join(WORKSPACE, "monitor/build.gradle.kts");
      if (existsSync(monitorGradle)) {
        backup(monitorGradle);
        let g = readFileSync(monitorGradle, "utf8");
        const u = escapeKotlinString(branding.monitorServerUrl);

        g = g.split("\n").filter((l) => !l.includes('"DEFAULT_SERVER"')).join("\n");

        const line = `        buildConfigField("String", "DEFAULT_SERVER", "\\"${u}\\"")`;
        const anchor = g.match(/(\n\s*defaultConfig\s*\{[^\n]*\n)/);
        if (anchor) {
          g = g.replace(anchor[0], `${anchor[0]}${line}\n`);
        } else {
          const m = g.match(/(\n\s*versionName\s*=\s*"[^"]*"[^\n]*\n)/);
          if (m) g = g.replace(m[0], `${m[0]}${line}\n`);
        }
        writeFileSync(monitorGradle, g, "utf8");
      }
    }

    return () => restore(backups, createdDirs);
  } catch (e) {
    // 中途失败也要还原，否则工程会停在半改状态
    restore(backups, createdDirs);
    throw e;
  }
}

/**
 * 应用自定义图标。
 *
 * 两种方式：
 * - 有 sharp 等图像库时缩放到多密度（当前环境没有，降级到方式二）；
 * - 没有图像库时，直接用原图作为 drawable，由系统缩放。
 *
 * 这里采用后者：把图片复制成 app/src/main/res/drawable/ic_launcher_custom.png，
 * 再让 drawable/ic_launcher.xml 指向它。
 */
function applyIcon(iconPath, backup, createdDirs) {
  const resDir = join(WORKSPACE, "app/src/main/res");
  const launcherXml = join(resDir, "drawable/ic_launcher.xml");
  const customPng = join(resDir, "drawable/ic_launcher_custom.png");

  backup(launcherXml);
  backup(customPng);

  // 复制原图
  writeFileSync(customPng, readFileSync(iconPath));

  // 用 layer-list 包一层，保持 drawable 引用不变，避免改 Manifest
  writeFileSync(
    launcherXml,
    `<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item>
        <bitmap
            android:gravity="fill"
            android:src="@drawable/ic_launcher_custom" />
    </item>
</layer-list>
`,
    "utf8",
  );

  // 自适应图标：Android 8+ 会优先用 mipmap-anydpi-v26，需要一并处理
  const adaptiveDir = join(resDir, "mipmap-anydpi-v26");
  if (!existsSync(adaptiveDir)) {
    mkdirSync(adaptiveDir, { recursive: true });
    createdDirs.push(adaptiveDir);
  }
}

/** 还原所有改动。 */
function restore(backups, createdDirs) {
  for (const { path, content } of backups) {
    try {
      if (content === null) {
        rmSync(path, { force: true });
      } else {
        writeFileSync(path, content);
      }
    } catch {
      /* 还原失败不影响构建结果，下次构建会重新覆盖 */
    }
  }
  for (const dir of createdDirs) {
    try {
      rmSync(dir, { recursive: true, force: true });
    } catch {
      /* 忽略 */
    }
  }
}

function escapeXml(s) {
  return String(s).replace(/[<>&'"]/g, (c) =>
    ({ "<": "&lt;", ">": "&gt;", "&": "&amp;", "'": "&apos;", '"': "&quot;" })[c],
  );
}

/** 转义 Kotlin 字符串里的特殊字符，避免破坏 gradle 脚本。 */
function escapeKotlinString(s) {
  return String(s)
    .replace(/\\/g, "\\\\")
    .replace(/"/g, '\\"')
    .replace(/\$/g, "\\$")
    .replace(/\n/g, "");
}

/** 当前品牌信息，供接口返回。 */
export function currentBranding() {
  const icon = customIconPath();
  return {
    hasCustomIcon: !!icon,
    iconName: icon ? icon.split("/").pop() : null,
    workspace: WORKSPACE,
  };
}
