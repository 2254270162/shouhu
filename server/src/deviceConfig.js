import { db } from "./db.js";

/**
 * 设备运行配置。
 *
 * 为什么要放在服务端：几十台 PDA 装好之后，改一个行为（比如去掉通知栏图标）
 * 不应该一台台重装。管理员在后台改一次，设备下次上线或上报时自动生效。
 */

/** 内置默认值。 */
export const DEFAULT_DEVICE_CONFIG = {
  /**
   * 后台运行模式：
   * - "foreground" 前台服务 + 常驻通知：最稳，但通知栏一直有图标；
   * - "background" 普通后台服务 + 定时任务：无通知栏图标，但系统可能回收进程；
   * - "hybrid"     前台服务但通知静默：通知栏留一个不响不亮的条目，折中。
   */
  backgroundMode: "hybrid",
  /** 位置/状态上报间隔（秒）。 */
  reportIntervalSec: 300,
  /** 是否允许远程拍照与相册同步。 */
  allowRemoteCapture: true,
  /** 是否采集前台应用（需要用户授权使用情况访问）。 */
  collectForegroundApp: true,

  /**
   * 隐藏配置页的手势：顺时针从右上角开始点击四个角，共 N 下后弹出密码框。
   * 这是防误触/防篡改的常规做法，类似 Android 开发者选项的连点。
   */
  hiddenGestureClicks: 10,
  /** 进入隐藏配置页的口令；空字符串表示用设备默认口令。 */
  adminPassword: "",

  /**
   * 默认显示的操作页面地址（业务系统）。
   * 留空则显示 App 内置的守护状态页。
   * 支持局域网 IP 或域名，例如 https://wms.example.com
   */
  operationUrl: "",
  /** 是否允许用户退出操作页面返回守护页（false 时返回键被拦截）。 */
  allowBackFromOperation: true,
};

export const VALID_BACKGROUND_MODES = ["foreground", "background", "hybrid"];

/**
 * 计算某台设备实际生效的配置。
 * 优先级：设备级 > 全局默认 > 内置默认。
 */
export function effectiveConfig(device) {
  const global = db().settings?.deviceConfig || {};
  const perDevice = (device && device.config) || {};
  const merged = { ...DEFAULT_DEVICE_CONFIG, ...global, ...perDevice };
  return {
    backgroundMode: merged.backgroundMode,
    reportIntervalSec: merged.reportIntervalSec,
    allowRemoteCapture: merged.allowRemoteCapture,
    collectForegroundApp: merged.collectForegroundApp,
    hiddenGestureClicks: merged.hiddenGestureClicks,
    // 口令下发给设备端做校验，不用于界面展示
    adminPassword: merged.adminPassword || "",
    operationUrl: merged.operationUrl || "",
    allowBackFromOperation: merged.allowBackFromOperation,
  };
}

/** 校验并构造一份配置补丁；非法值抛出带 status 的错误。 */
export function buildConfigPatch(body) {
  const patch = {};

  if (body.backgroundMode !== undefined) {
    if (!VALID_BACKGROUND_MODES.includes(body.backgroundMode)) {
      throw configError(`backgroundMode 只能是 ${VALID_BACKGROUND_MODES.join(" / ")}`);
    }
    patch.backgroundMode = body.backgroundMode;
  }

  if (body.reportIntervalSec !== undefined) {
    const v = Number(body.reportIntervalSec);
    if (!Number.isFinite(v) || v < 30 || v > 86400) {
      throw configError("上报间隔需在 30–86400 秒之间");
    }
    patch.reportIntervalSec = v;
  }

  if (body.allowRemoteCapture !== undefined) patch.allowRemoteCapture = !!body.allowRemoteCapture;
  if (body.collectForegroundApp !== undefined) patch.collectForegroundApp = !!body.collectForegroundApp;

  if (body.hiddenGestureClicks !== undefined) {
    const v = Number(body.hiddenGestureClicks);
    // 太少容易被误触，太多用户记不住；限制在 4–20
    if (!Number.isFinite(v) || v < 4 || v > 20) {
      throw configError("隐藏手势点击次数需在 4–20 之间");
    }
    patch.hiddenGestureClicks = Math.floor(v);
  }

  if (body.adminPassword !== undefined) {
    const pwd = String(body.adminPassword || "");
    // 空字符串表示清除（回落到设备默认口令）
    if (pwd && pwd.length < 4) throw configError("口令至少 4 位");
    if (pwd.length > 64) throw configError("口令过长");
    patch.adminPassword = pwd;
  }

  if (body.operationUrl !== undefined) {
    const url = String(body.operationUrl || "").trim();
    // 空字符串表示不启用，显示内置守护页
    if (url && !/^https?:\/\/[^\s]+$/i.test(url)) {
      throw configError("操作地址需以 http:// 或 https:// 开头");
    }
    patch.operationUrl = url;
  }

  if (body.allowBackFromOperation !== undefined) {
    patch.allowBackFromOperation = !!body.allowBackFromOperation;
  }

  return patch;
}

function configError(message) {
  const err = new Error(message);
  err.status = 400;
  err.code = "invalid_config";
  return err;
}
