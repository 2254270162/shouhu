import { randomToken } from "./auth.js";
import { db, persist } from "./db.js";

/**
 * 设备分组。
 *
 * 场景：几十台 PDA 分属不同部门/区域/批次，需要分组查看和管理。
 *
 * 设计取舍：
 * - 分组是「标签」而不是「容器」：一个设备只属于一个分组，但不影响它的身份；
 * - 分组只用于展示与批量操作，不参与鉴权（权限仍由管理员账号控制）；
 * - 组密钥注册时可以直接指定分组，装完就自动归组。
 */

/** 预置颜色，避免用户输入非法值导致界面上看不清。 */
export const GROUP_COLORS = [
  "#4f46e5", // 靛蓝
  "#0891b2", // 青
  "#059669", // 绿
  "#d97706", // 橙
  "#dc2626", // 红
  "#7c3aed", // 紫
  "#64748b", // 灰
];

export function validateGroupName(name) {
  const n = String(name || "").trim();
  if (!n) throw new Error("分组名称不能为空");
  if (n.length > 20) throw new Error("分组名称不超过 20 个字");
  return n;
}

export function listGroups() {
  const groups = Object.values(db().groups || {});
  // 附带每组的设备数，方便界面直接显示
  const devices = Object.values(db().devices || {});
  return groups
    .map((g) => ({
      ...g,
      deviceCount: devices.filter((d) => d.groupId === g.id).length,
      onlineCount: devices.filter((d) => d.groupId === g.id && d.online).length,
    }))
    .sort((a, b) => a.createdAt - b.createdAt);
}

export function createGroup(name, color, note) {
  const n = validateGroupName(name);
  // 重名校验：同名分组会让批量操作产生歧义
  const exists = Object.values(db().groups || {}).some((g) => g.name === n);
  if (exists) throw new Error(`分组「${n}」已存在`);

  const id = `grp_${randomToken(6).replace(/[^A-Za-z0-9]/g, "").slice(0, 10)}`;
  db().groups = db().groups || {};
  db().groups[id] = {
    id,
    name: n,
    color: GROUP_COLORS.includes(color) ? color : GROUP_COLORS[0],
    note: String(note || "").slice(0, 100),
    createdAt: Date.now(),
  };
  persist();
  return db().groups[id];
}

export function updateGroup(id, patch) {
  const g = db().groups?.[id];
  if (!g) {
    const err = new Error("分组不存在");
    err.status = 404;
    err.code = "group_not_found";
    throw err;
  }
  if (patch.name !== undefined) {
    const n = validateGroupName(patch.name);
    const dup = Object.values(db().groups).some((x) => x.id !== id && x.name === n);
    if (dup) throw new Error(`分组「${n}」已存在`);
    g.name = n;
  }
  if (patch.color !== undefined && GROUP_COLORS.includes(patch.color)) g.color = patch.color;
  if (patch.note !== undefined) g.note = String(patch.note).slice(0, 100);
  persist();
  return g;
}

/**
 * 删除分组。
 *
 * 不删设备：把组内设备的 groupId 清空即可。
 * 删设备是另一件更危险的事，不该由删分组顺带触发。
 */
export function deleteGroup(id) {
  const g = db().groups?.[id];
  if (!g) return { removed: false, detached: 0 };

  let detached = 0;
  for (const d of Object.values(db().devices || {})) {
    if (d.groupId === id) {
      d.groupId = null;
      detached++;
    }
  }
  delete db().groups[id];
  persist();
  return { removed: true, detached };
}

/** 把设备分配到分组（groupId 传 null 表示移出分组）。 */
export function assignDevice(deviceId, groupId) {
  const d = db().devices?.[deviceId];
  if (!d) {
    const err = new Error("设备不存在");
    err.status = 404;
    err.code = "device_not_found";
    throw err;
  }
  if (groupId !== null && !db().groups?.[groupId]) {
    const err = new Error("分组不存在");
    err.status = 404;
    err.code = "group_not_found";
    throw err;
  }
  d.groupId = groupId;
  persist();
  return d;
}

/** 批量分配。 */
export function bulkAssign(deviceIds, groupId) {
  if (groupId !== null && !db().groups?.[groupId]) {
    const err = new Error("分组不存在");
    err.status = 404;
    err.code = "group_not_found";
    throw err;
  }
  let changed = 0;
  for (const id of deviceIds) {
    const d = db().devices?.[id];
    if (!d) continue;
    d.groupId = groupId;
    changed++;
  }
  persist();
  return changed;
}

/** 设备所属分组的信息（供列表拼接展示）。 */
export function groupOf(device) {
  if (!device?.groupId) return null;
  const g = db().groups?.[device.groupId];
  if (!g) return null;
  return { id: g.id, name: g.name, color: g.color };
}
