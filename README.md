# Amusic Android App (Web 版增强型安卓客户端)

基于原生 Android (Kotlin) 打造的 **Amusic 增强型 Web 容器客户端**。

以极简、稳定、精准为第一原则：**界面 100% 运行网页原版**（零 UI 重写与像素级无缝还原），同时在 Android 原生层赋予其**专业级音乐 App 的后台保活、通知栏锁屏播控与外设控制能力**。

---

## ✨ 核心特性

1. **100% 网页原版体验**：
   - 采用 Android 硬件加速 WebView，完整保留 `Amusic` 精密的响应式布局（Apple Music + 极简工控美学）、7 套内置主题切换、双态歌词停靠栏、多选批量操作等所有原生网页功能。
2. **后台播放绝不断音 (Foreground Service)**：
   - 原生常驻 `MusicService`，向 Android 系统声明 `mediaPlayback` 前台服务，彻底免疫系统的后台省电与冻结策略（Doze 模式）。
3. **系统级通知栏与锁屏播控 (MediaSessionCompat)**：
   - 锁屏与系统下拉通知栏展示高清歌曲封面、歌曲名、歌手与专辑名。
   - 支持系统控制台直接点击「上一曲」、「播放/暂停」、「下一曲」。
   - 完美响应蓝牙耳机按键与线控。
4. **音频焦点智能管理**：
   - 接入系统 `AudioManager`，在电话呼入、微信语音或地图导航语音播报时自动暂停/恢复。
5. **灵活的服务器地址配置**：
   - 默认访问：`http://amusic.hefang.fun`。
   - 支持随时切换：在 **网络断开错误页** 中，可随时输入更换局域网服务器（如 `http://192.168.x.x:8088`）或其他云端域名，设置即时保存生效。
6. **轻量纯粹**：
   - 无任何冗余跨平台引擎（无 Flutter/React Native/Capacitor 额外开销），编译产物仅约 6.7MB。

---

## 🛠️ 工程结构

```
amusic-app/
├── app-debug.apk                        # 编译生成的直接安装包 (~6.9MB)
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml          # 权限声明 (明文HTTP放行、前台媒体服务、通知)
│   │   ├── res/
│   │   │   ├── layout/activity_main.xml # 全屏沉浸式 WebView 与网络重试换源界面
│   │   │   └── xml/network_security_config.xml # 明文 HTTP 流量白名单配置
│   │   └── java/com/amusic/app/
│   │       ├── MainActivity.kt          # 核心 Activity，负责容器生命周期与调度
│   │       ├── bridge/
│   │       │   ├── AmusicWebBridge.kt   # WebView 状态监听、精准 JS 注入与指令派发
│   │       │   └── WebAppInterface.kt   # 原生与前端 JS 交互契约
│   │       ├── media/
│   │       │   ├── MusicService.kt      # 前台服务生命周期保活与通知刷新
│   │       │   ├── MediaSessionManager.kt # 系统锁屏/通知栏/蓝牙外设播控中枢
│   │       │   ├── AudioFocusHelper.kt  # 音频焦点按需申请与优雅释放
│   │       │   └── ArtworkLoader.kt     # 封面单例加载与防内存溢出缩放
│   │       └── ui/
│   │           └── ServerConfigDialog.kt # 服务器换源弹窗
│   └── build.gradle.kts
├── build.gradle.kts
└── gradle.properties                   # JDK 17 与构建配置
```

---

## 🚀 编译与构建

项目使用 JDK 17 与 Gradle 8.7 构建：

```bash
cd /home/yytak/gd/MyFiles/project/amusic-app
JAVA_HOME=/home/yytak/jdk-17 bash gradlew assembleDebug
```

编译产物将生成在 `/home/yytak/.cache/amusic-app-build/app/outputs/apk/debug/app-debug.apk`，根目录下已同步放置 [`app-debug.apk`](file:///home/yytak/gd/MyFiles/project/amusic-app/app-debug.apk)。
