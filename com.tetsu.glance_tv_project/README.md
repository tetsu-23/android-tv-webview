# Glance Android TV APK (`com.tetsu.glance`)

这是一个专为 Android TV 定制的极简全屏 WebView 应用，启动后直接加载 `http://192.168.1.144:6278`，无地址栏、不支持更换网址、无遥控器焦点框干扰，并保持电视屏幕常亮。

## 核心配置说明
- **包名 (Application ID)**: `com.tetsu.glance`
- **固定加载地址**: `http://192.168.1.144:6278`
- **局域网 HTTP 支持**: 已开启 `android:usesCleartextTraffic="true"`（支持直接访问内网非 HTTPS 地址）
- **Android TV 桌面支持**: 已配置 `LEANBACK_LAUNCHER` 与 TV 横幅图标 (`tv_banner.png`)
- **无遥控器交互 & 常亮**: 已关闭 WebView 焦点 (`focusable="false"`) 与滚动条，开启 `FLAG_KEEP_SCREEN_ON` 防止电视自动休眠，且若内网服务重启会自动每 5 秒重连。

---

## 如何免费自动打包生成 APK（无需安装 Android Studio）

### 方法一：使用 GitHub Actions 云端一键编译（最省事）
1. 在 GitHub 上创建一个新的仓库（公开或私有均可）。
2. 将解压后的所有文件（注意包含隐藏文件夹 `.github/workflows/build.yml`）上传并提交到仓库。
3. 点击仓库顶部的 **Actions** 标签页，你会看到 `Build Glance TV APK` 正在自动运行（也可以手动点击 `Run workflow`）。
4. 等待约 1~2 分钟构建变绿完成后，点进该任务页面底部的 **Artifacts** 区域，点击下载 `com.tetsu.glance-tv-apk` 即可获得可直接安装的 `.apk` 文件。

### 方法二：本地使用 Android Studio 编译
1. 用 Android Studio 打开解压后的项目文件夹。
2. 等待 Gradle 同步完成后，点击顶部菜单 **Build -> Build Bundle(s) / APK(s) -> Build APK(s)**。
3. 在 `app/build/outputs/apk/debug/app-debug.apk` 即可拿到安装包。
