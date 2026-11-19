# 🌧 停云

**Android 挂机页** — 雨、白噪音、本地音乐，界面自动淡出

[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com)
[![Release](https://img.shields.io/github/v/release/a2006-dev/TingYun)](https://github.com/a2006-dev/TingYun/releases)

名字取自陶渊明《停云》——「霭霭停云，濛濛时雨」。

---

| 项目 | 说明 |
|---|---|
| 包名 | `com.tingyun.app` |
| 最低版本 | Android 8.0（API 26） |
| 编译版本 | SDK 34 |
| 界面 | 本地 HTML + WebView |
| 音频 | Media3 ExoPlayer |
| 许可证 | MIT |

---

## ✨ 功能

- **本地播放** — 基于 Media3 ExoPlayer，支持通知栏、蓝牙耳机、锁屏控制
- **加密格式解密** — 导入时自动识别并解密 NCM / QMC，无需先转码
- **标签读取** — 内嵌封面、歌手、专辑、内嵌歌词；支持外挂 `.lrc`
- **雨景背景** — 三支内置视频循环播放，透明度可调
- **白噪音混音** — 雨 / 火 / 林 / 浪 四路，与音乐独立调节
- **静态壁纸** — 相册选图，内置裁剪
- **元素淡出** — 空闲后界面渐隐，延迟与最终亮度均可调
- **歌词微调** — 偏移量可调，处理时间轴偏差
- **显示控制** — 全屏、横竖屏锁定

---

## 📋 前置条件

| 项目 | 要求 |
|---|---|
| 系统 | Android 8.0（API 26）及以上 |
| 存储 | 约 40 MB（含内置素材） |
| 权限 | 通知、媒体读取、前台服务 |
| 网络 | 不需要，App 不联网 |

**仓库不含任何音乐文件**，安装后曲库为空，需自行导入。

---

## 🚀 快速开始

### 直接安装

从 [Releases](https://github.com/a2006-dev/TingYun/releases) 下载 `tingyun-1.0.apk` 安装即可。

### 从源码构建

```bash
# 1. 克隆
git clone https://github.com/a2006-dev/TingYun.git
cd TingYun

# 2. 构建
./gradlew assembleRelease

# 3. 产物位置
# app/build/outputs/apk/release/app-release.apk
```

需要 Android SDK 34 与 JDK 17。

签名配置从根目录的 `keystore.properties` 读取（该文件未纳入仓库）。自行构建时创建这个文件即可：

```properties
storeFile=app/heart-release.p12
storePassword=你的密码
keyAlias=你的别名
keyPassword=你的密码
```

文件不存在时会跳过 release 签名，debug 构建不受影响。

---

## 📖 使用说明

### 首次配置

1. 打开 App，进入首页
2. 点击导入，选择本地音乐文件（支持 NCM / QMC）
3. 选择雨景背景、调整透明度
4. 进入挂机页

### 挂机页交互

| 操作 | 效果 |
|---|---|
| 点击屏幕 | 唤出控制条 |
| 上滑 | 展开抽屉 |
| 抽屉内 | 音量、白噪音、歌词偏移、淡出设置、壁纸切换 |
| 空闲 | 按设定延迟与亮度淡出 |

### 元素淡出设置

抽屉内的「元素淡出」提供三个参数：

- **开关** — 关闭后界面常亮
- **延迟** — 0~60 秒，0 表示松手立即开始淡出
- **亮度** — 0~100%，最终保留的透明度

---

## 🏗️ 技术架构

```
WebView (assets/player.html)
        │  @JavascriptInterface
        ▼
TingYunBridge ──┬── AudioEngine (ExoPlayer)
                ├── AmbientEngine (白噪音混音)
                ├── MediaSessionHolder (通知栏)
                ├── Importer ── NcmDecoder / QmcDecoder
                └── LibraryStore / Prefs
```

界面资源通过 `WebViewAssetLoader` 从 `appassets.androidplatform.net` 加载，不用 `file://`。

### 关键依赖

| 依赖 | 用途 |
|---|---|
| `media3-exoplayer` 1.1.1 | 音频播放 |
| `media3-session` 1.1.1 | 媒体会话与通知 |
| `androidx.webkit` 1.6.1 | WebViewAssetLoader |
| `appcompat` 1.6.1 | 兼容层 |

---

## 📁 项目结构

```
app/src/main/
├── java/com/tingyun/app/
│   ├── MainActivity.java        WebView 容器与权限
│   ├── TingYunBridge.java       JS ↔ Java 接口
│   ├── AudioEngine.java         ExoPlayer 封装
│   ├── MediaSessionHolder.java  媒体会话与通知
│   ├── AmbientEngine.java       白噪音混音
│   ├── Importer.java            导入流程
│   ├── NcmDecoder.java          NCM 解密
│   ├── QmcDecoder.java          QMC 解密
│   ├── Cipher.java              加解密原语
│   ├── LibraryStore.java        曲库持久化
│   ├── Prefs.java               设置读写
│   ├── TagReader.java           音频标签解析
│   ├── LrcParser.java           LRC 解析
│   ├── LyricProvider.java       歌词统一入口
│   ├── Track.java               曲目模型
│   └── WallCropActivity.java    壁纸裁剪
└── assets/
    ├── index.html               首页
    ├── player.html              挂机页
    ├── video/                   雨景视频
    ├── sfx/                     白噪音
    └── fonts/                   霞鹜文楷子集
```

---

## 📄 许可证

本项目基于 MIT 许可证开源 — 详见 [LICENSE](LICENSE) 文件。

字体为 [霞鹜文楷](https://github.com/lxgw/LxgwWenKai)，SIL Open Font License 1.1，仅打包页面实际使用的字符子集。

---

*Made with ❤️ by a2006-dev*