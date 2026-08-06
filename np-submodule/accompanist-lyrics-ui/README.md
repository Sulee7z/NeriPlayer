<img src="./artwork/banner.png" style="border-radius: 6px; margin-bottom: 8px">

[![Download](https://img.shields.io/badge/Download-DEMO%20APK-green?logo=github)](https://github.com/6xingyv/Accompanist/releases/latest)
[![Maven Central](https://img.shields.io/maven-central/v/com.mocharealm.accompanist/lyrics-ui)](https://central.sonatype.com/artifact/com.mocharealm.accompanist/lyrics-ui)
[![Telegram](https://img.shields.io/badge/Telegram-Community-blue?logo=telegram)](https://t.me/mocha_pot)

> [!IMPORTANT]
> 此目录是 `NeriPlayer` 工程内维护的 `accompanist-lyrics` fork 子模块，用于项目集成与定制修改；它**不是**上游官方仓库。若你要查阅官方说明、发布、Issue 或提交记录，请优先访问上游项目，并以原作者/上游仓库信息为准。  
> This directory is a `NeriPlayer`-maintained forked `accompanist-lyrics` submodule for project integration and custom changes. It is **not** the official upstream repository. If you are looking for official docs, releases, issues, or commit history, please refer to the upstream project and treat the original authors/upstream repository as the source of truth.

## 📦 Repository

Accompanist released a group of artifacts, including: 

- [`lyrics-core`](https://github.com/6xingyv/Accompanist-Lyrics) - Parsing lyrics file, holding data and exporting to other formats.

- [`lyrics-ui`](https://github.com/6xingyv/Accompanist) - Standard lyrics interface built on Jetpack Compose

This repository hosts the `lyrics-ui` code.

## ✨ Features

- **🎤 Multi-Voice & Duet Support**: Effortlessly display lyrics for multiple singers.

- **🎶 Accompaniment Line Support**: Renders accompaniment lines separately from main vocals.

- **⚡️ High-Performance Rendering**: Engineered for buttery-smooth animations and low overhead, ensuring a great user experience even on complex lyrics.

## 🚀 Installation

NeriPlayer consumes this fork as the local `:accompanist-lyrics-ui` module mapped to this
submodule's `src` directory; no Maven dependency is needed inside NeriPlayer.

External projects that want the upstream published artifact can use:

```kotlin
dependencies {
    implementation("com.mocharealm.accompanist:lyrics-ui:VERSION")
}
```

*Replace `VERSION` with an upstream version from Maven Central. The published artifact may not
contain NeriPlayer fork-only rendering changes.*

## ✅ Todo

- [x] Spring placement animations for lyric list items during automatic scrolling
- [ ] Extract animation parameters from `KaraokeLineText`
- [ ] More precise animation parameters from Apple
- [ ] Mesh gradient/Image distortion `FlowingLightBackground` animation

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a pull request or open an issue to discuss your ideas. For major changes, please open an issue first.


## 📜 License

This project is licensed under the **Apache License 2.0**. See the [LICENSE](http://www.apache.org/licenses/LICENSE-2.0.txt) file for details.
