# 行车记录仪Lite

无广告的行车记录仪伴侣。用 Kotlin 和 Jetpack Compose 重新实现本地 Wi-Fi 协议，不包含原版的账号、论坛、推送和广告。

包名 `com.niasand.dashcamlite`，应用名「行车记录仪Lite」，minSdk 26，targetSdk 36。

## 能做什么

- 绑定没有互联网的记录仪热点（`requestNetwork` + `bindProcessToNetwork`，Android 10 及以上可用 `WifiNetworkSpecifier`）
- 自动识别联咏、SigmaStar/MStar、海思、国科微、凌通 Grain、杰理
- 预览、浏览普通/紧急/照片、多选下载到相册 `Movies/DashcamLite` 或 `Pictures/DashcamLite`，支持断点续传和失败重试
- 离开预览、退到后台、划掉任务时发送原版相同的恢复录像指令
- 设置、校时、存储卡信息和带确认框的格式化、固件信息

## 签名

仓库是公开的，**不要**把 keystore、密码或反编译源码提交进来。`.gitignore` 已忽略 `*.jks` 和 `*.keystore`。

本地或 CI 若设置了这些环境变量，`assembleRelease` 会用它们签名：

- `SIGNING_KEYSTORE`：keystore 文件路径（CI 里由 `SIGNING_KEYSTORE_BASE64` 解码得到）
- `SIGNING_STORE_PASSWORD`
- `SIGNING_KEY_ALIAS`
- `SIGNING_KEY_PASSWORD`

GitHub Actions 在上述 Secrets 齐全时用它们签名；否则 release 回退到 debug 签名，工作流仍然可以通过。

## 构建

需要 JDK 17 和 Android SDK 36。

```bash
./gradlew test assembleDebug assembleRelease
```

协议解析和模拟记录仪测试在 `:protocol`。
