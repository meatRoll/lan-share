# GitHub Release 构建

## 一次性准备

发布仓库以本地 Git 远端配置为准。提交身份按项目维护规范核对并沿用仓库历史中已确认的身份，不修改全局配置。上传前检查 Git 忽略规则，不要上传临时记录、临时文件、历史日志、APK、签名密钥、Web 接收文件或本机发现状态。

1. 创建并妥善备份正式 Android 签名密钥，不使用 debug 密钥。三个平台可以使用同一正式密钥。
2. 在 GitHub 仓库 Settings → Secrets and variables → Actions 配置：
   - `ANDROID_KEYSTORE_BASE64`：正式 keystore 的 Base64 内容。
   - `ANDROID_KEYSTORE_PASSWORD`：keystore 密码。
   - `ANDROID_KEY_ALIAS`：签名别名。
   - `ANDROID_KEY_PASSWORD`：密钥密码。
3. 使用 `main` 或 `master` 发布分支；其他分支名需修改工作流。
4. 工作流需获准使用 `contents: write` 创建 Release。Secrets 不应出现在日志或仓库中。

签名与测试版不同，正式版不能直接覆盖 debug 版。用户已同意卸载测试版后安装；卸载可能删除应用设置/私有文件，必要时先备份。

## 触发规则

工作流 `.github/workflows/android-release.yml` 在 main/master 的 push 涉及 `gradle.properties` 时启动，对比该 push 前后的属性：

- 仅变更版本的 Android 平台进入构建矩阵。
- 要同时更新该平台三段数字 versionName 与递增的 versionCode。
- 三平台版本均不变（仅修改其他属性）时不构建。
- 首次向新分支推送（before 为全零）构建全部当前平台，建立发布基线。
- 同一个 push 包含多提交时，对比 push 前后，不只比较 HEAD 的父提交。
- Web 没有 Android APK，不参与此矩阵。

每个平台生成独立 GitHub Release，标签例如 `tvModern-v0.5.12`，上传正式签名 APK，同时保存 Actions artifact。重跑同一工作流会复用 Release 并覆盖同名资产。

版本策略见 `AGENTS.md`。无需本地 assemble，也不要把 APK 加入 Git。

## 下载构建产物

本机目录 `release-downloads/` 用于下载 GitHub Release / Actions APK，整个目录被 `.gitignore` 忽略。不放源码、签名密钥或配置文件。GitHub 构建成功后下载 APK 到该目录；私有仓库下载需要认证，不把 token 写入脚本或 URL。

## 当前发布前仍需处理

工作流已准备，但并不代表正式版清理完成。按正式发布验收要求检查；当前 CI 用 `-PenableLogs=false` 关闭诊断，运行时诊断的正式可选方案及日志初始化/UI 清理尚待完成。不要在未验收时将版本变更推送至发布分支，因为构建成功后会自动创建 Release。

本地验证可以运行：

```sh
python3 scripts/test_release_matrix.py
node web/ui.test.js
node web/receive-tcp.test.js
node web/discovery.test.js
# 使用本机 Gradle 8.9 执行 compile...Kotlin，而非 assemble
```
