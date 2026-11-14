import { db, persist } from "./db.js";

/**
 * 权限的元数据与远程请求队列。
 *
 * 为什么要排队而不是直接下发：
 * Android 不允许后台服务弹权限框，也不允许后台拉起界面。
 * 所以「远程请求权限」只能是「留下一个待办」，等用户主动打开 App 时才弹窗。
 * 队列存在服务端，设备下次进前台会来取。
 */

/** 已知权限项，服务端用它做校验和展示。 */
export const KNOWN_PERMISSIONS = {
  location: { label: "定位", special: false, desc: "上报设备位置" },
  camera: { label: "相机", special: false, desc: "远程拍照" },
  notification: { label: "通知", special: false, desc: "前台服务需要常驻通知" },
  media: { label: "相册", special: false, desc: "同步照片" },
  usage_access: { label: "使用情况访问", special: true, desc: "读取前台应用，需手动在设置里开启" },
};

/**
 * 登记一次权限请求。
 *
 * @param {string} deviceId
 * @param {string[]} keys 要请求的权限 key 列表；空数组合法，表示「全部缺失的」
 */
export function enqueuePermissionRequest(deviceId, keys) {
  const device = db().devices[deviceId];
  if (!device) throw new Error("设备不存在");

  // 过滤掉不认识的 key，避免脏数据
  const wanted = Array.isArray(keys) ? keys.filter((k) => KNOWN_PERMISSIONS[k]) : [];

  device.permissionRequest = {
    keys: wanted,
    requestedAt: Date.now(),
    // 标记未消费：设备进前台时取走并清掉
    consumed: false,
  };
  persist();
  return device.permissionRequest;
}

/** 取出待处理的权限请求（设备端调用）。取走后标记为已消费。 */
export function takePermissionRequest(deviceId) {
  const device = db().devices[deviceId];
  if (!device) return null;
  const req = device.permissionRequest;
  if (!req || req.consumed) return null;

  // 超过 24 小时的请求自动作废，避免用户下次打开时弹出很久以前的请求
  if (Date.now() - req.requestedAt > 24 * 60 * 60 * 1000) {
    device.permissionRequest = null;
    persist();
    return null;
  }

  req.consumed = true;
  req.consumedAt = Date.now();
  persist();
  return req;
}
