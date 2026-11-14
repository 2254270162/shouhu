# 设备守护（Guardian）

一套自用的设备位置共享与状态查看系统，包含三个部分：

| 组件 | 说明 |
|---|---|
| **被监控端** | Android 应用，装在需要被查看的设备上，上报位置与运行状态 |
| **监控端** | Android 应用，管理员用来查看设备、下发指令 |
| **服务端** | Node.js 服务，提供 Web 管理后台、数据存储与 APK 编译 |

---

## ⚠️ 免责声明

**请在使用前完整阅读本节。**

本项目是一个**透明、可见**的设备管理工具，设计用途是：

- 管理自己名下的备用机、公司配发的 PDA 等自有设备；
- 家庭成员之间**知情同意**的位置共享；
- 企业内部设备的合规管理（建议配合 MDM 方案）。

**明确禁止**用于以下场景：

- 在他人不知情的情况下安装、隐藏或监控其设备；
- 跟踪配偶、伴侣、员工、未成年人等未经同意的对象；
- 任何违反当地法律法规的用途。

**本项目的技术边界（刻意设计，不可关闭）**：

1. 被监控端**始终在通知栏显示**「设备守护中」，用户点开可查看详情并随时停止；
2. 不上报通讯录、短信、通话记录、社交软件聊天内容；
3. 不隐藏应用图标，不做静默安装；
4. 不提供「绕过权限」「破解锁屏」等能力。

**使用本软件即表示**：你已知晓并同意仅在合法、合规、经被监控方知情同意的场景下使用。
开发者不对任何滥用行为承担责任。

---

## 功能概览

### 被监控端

- 位置上报（含精度、来源、时间戳）
- 运行状态：电量、电池温度、充电状态、屏幕开关、内存、存储、前台应用
- 远程拍照、相册同步（需用户主动开启开关）
- 独立的设备日志，便于事后审计
- 息屏保活（电池优化白名单 + 唤醒锁）
- 预置注册：编译时写入服务器地址与组密钥，装完自动注册

### 监控端

- 管理员账号密码登录（与网页端共享同一套账号）
- 设备列表、分组筛选
- 下发指令：定位、拍照、同步相册、刷新状态、读取剪贴板
- 查看设备日志、照片
- 打开设备管理页（服务端下发指令，设备端确认）
- 预置注册配置与 APK 编译

### 服务端 / Web 后台

- 设备管理、分组、照片、日志
- 管理员账号与会话
- APK 在线编译（可自定义应用名、包名、图标）
- APK 分发与下载页
- 预置注册（组密钥）管理

---

## 快速开始

### 方式一：直接运行服务端

需要 Node.js 22 或更高版本。

```bash
cd server
npm install
node src/index.js
```

启动后终端会打印局域网访问地址，例如：

```
本机访问   http://127.0.0.1:8080
局域网访问 http://192.168.1.100:8080
```

浏览器打开该地址，首次会引导创建管理员账号。

### 方式二：使用打包好的 exe（Windows）

```bash
cd server
npm install
node build-exe.mjs
```

产物在 `server/dist/guardian-server.exe`，双击即可运行，数据保存在 exe 同级的 `guardian-data/`。

如需在离线局域网内编译 APK，可生成带构建环境的完整版（体积约 2.2GB）：

```bash
# 先准备好 JDK、Gradle、Android SDK 到 .toolchain 目录
node build-exe.mjs --with-build-env
```

### 编译 Android 应用

```bash
./gradlew :app:assembleDebug      # 被监控端
./gradlew :monitor:assembleDebug  # 监控端
```

产物分别在 `app/build/outputs/apk/debug/` 和 `monitor/build/outputs/apk/debug/`。

---

## 目录结构

```
.
├── app/                     被监控端（Android）
│   └── src/main/java/com/example/locateme/
│       ├── MainActivity.kt          操作页 / 管理页入口
│       └── guard/
│           ├── GuardService.kt      前台服务：上报、指令处理
│           ├── GuardSocket.kt       WebSocket 长连接
│           ├── GuardApi.kt          HTTP 接口
│           ├── Telemetry.kt         运行状态采集
│           ├── PowerHelper.kt       息屏保活
│           ├── AdminGate.kt         管理页打开请求通道
│           └── CameraCapture.kt     后台拍照
│
├── monitor/                 监控端（Android）
│   └── src/main/java/com/example/monitor/
│       ├── MonitorApi.kt            HTTP 客户端（Session 认证）
│       ├── SessionCookieStore.kt    Cookie 持久化
│       └── MonitorScreen.kt         界面
│
└── server/                  服务端（Node.js）
    ├── src/
    │   ├── index.js                 入口
    │   ├── http.js                  路由与静态资源
    │   ├── routes.js                设备端接口
    │   ├── adminRoutes.js           管理端接口
    │   ├── ws.js                    WebSocket 与指令下发
    │   ├── deviceConfig.js          设备配置
    │   ├── deviceLog.js             设备日志
    │   ├── apkBuilder.js            APK 编译
    │   ├── branding.js              品牌定制
    │   ├── setup.js                 构建环境一键安装
    │   └── resources.js             exe 内嵌资源释放
    ├── public/index.html            管理后台（单文件）
    └── test/                        测试脚本
```

---

## 配置说明

### 服务端环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `PORT` | `8080` | 监听端口 |
| `HOST` | `0.0.0.0` | 监听地址 |
| `DATA_DIR` | `./data` | 数据目录（数据库、照片、APK） |
| `BUILD_ROOT` | 视平台而定 | APK 编译工作目录 |

### 设备端关键配置

在管理后台「运行配置」中可调：

- **后台模式**：`foreground`（常驻通知）/ `hybrid`（通知静默）/ `background`（无通知，但系统可能回收进程）
- **上报间隔**：30–86400 秒
- **操作页地址**：设备默认显示的网页，留空则显示转圈页
- **管理口令**：进入设备管理页需要输入的口令

### APK 编译时的品牌定制

在「安装包与编译」页可以设置：

- 应用名称、包名、图标
- 预置注册的服务器地址与组密钥（必须填写才能编译带注册的包）

---

## 平台限制说明

以下限制来自 Android 系统本身，不是实现缺陷：

| 限制 | 说明 |
|---|---|
| 无法后台弹权限框 | 权限申请必须由前台 Activity 发起 |
| 无法后台拉起界面 | Android 10+ 禁止，所以「打开管理页」是指令挂起，用户切回 App 时生效 |
| 剪贴板只能前台读取 | 后台读取返回 null |
| 「使用情况访问」需手动授权 | 系统不提供运行时弹窗，只能跳设置页 |
| 息屏后可能被回收 | 需要用户手动加入电池优化白名单 |

---

## 安全说明

- 管理员密码使用 scrypt 加盐哈希存储
- 会话通过 httpOnly Cookie 维持
- 设备令牌随机生成，一设备一令牌
- 预置注册的组密钥会随 APK 分发，**拿到安装包即可注册设备**，建议设置注册上限并定期轮换
- 建议全程使用 HTTPS（可用 Cloudflare Tunnel、Nginx 反代等方式）

---

## 测试

```bash
cd server
node test/smoke.mjs              # 基础冒烟
node test/monitor-flow-test.mjs  # 监控端全链路
node test/alignment-test.mjs     # 三端对齐
```

---

## 许可

仅供学习与自用。使用者需自行确保用途合法合规。
