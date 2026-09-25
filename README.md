# 懒得找了 · 移动端控制台（LzdzConsole）

独立的控制台 Android 应用仓库（不含本体软件源码）。

## 版本

- 当前版本：**v1.0.4**（versionCode 29）
- 修复内容：**根治「云端数据解析失败：Maximum call stack size exceeded」**

## v1.0.4 修复说明（重要）

### 错误根因
- 旧版 `index.html` 中有一段「跑马灯北京时间」功能代码，采用**全局函数包装**方式：
  ```js
  const _origRenderSettings = renderSettings;
  function renderSettings(){ _origRenderSettings.apply(this, arguments); ... }
  ```
- JS 函数声明提升导致 `_origRenderSettings` 捕获到的其实是包装后的 `renderSettings` 自身，
  每次调用都自我递归 → **无限套娃 → `Maximum call stack size exceeded`** → 被 catch 捕获后显示「云端数据解析失败」。
- 该问题由网页端（1.7.4 引入）的代码模式带入移动端，**与站点数量、CDN 无关**，一打开就必现。

### 修复方式
1. **删除套娃包装**：不再重定义 `renderSettings`，不再用 `_origRenderSettings`。
2. 在原版 `renderSettings` 函数体末尾直接调用 `startBeijingClock()`（函数声明提升，可安全调用）。
3. `startBeijingClock` **幂等化**（`__bjClockStarted` 标记），避免重复 `setInterval`。
4. 移除 1.7.4 网页端 / CLI 控制台代码（本仓库不包含 `console/` 网页版）。

### 已验证（node + vm 完整模拟）
- ✅ 脚本加载无栈溢出
- ✅ `connect()` 成功：`成功加载 admin-data.json · 17个分类 · 当前发布版本 v1.8.6 (code:75)`
- ✅ 首页 17 分类 + 设置页正常渲染

## 构建

```bash
./gradlew -p console-apk :app:assembleRelease
# 产物: console-apk/app/build/outputs/apk/release/app-release.apk
```

CI：打 tag（如 `v1.0.4-console`）自动构建并上传到 `dist/console/`、创建 Release。

## 数据源

- 连接仓库：本仓库（owner=shuting52，repo=lzdz-console）
- 数据文件：`admin-data.json`（控制台读取/写入的唯一数据源）
- 素材目录：`dist/uploads/`（二维码、开屏视频等，admin-data.json 引用）

## 使用

1. 安装 `dist/console/console-apk-v1.0.4-*.apk`（老版本会自动提示升级）
2. 打开控制台，粘贴有效 GitHub Token（需 repo 权限）
3. 点「连接」→ 自动加载云端数据，进行维护/发布