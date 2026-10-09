# GitHub Release 构建

## 一次性准备

构建与发布流程集中维护于本文件，使用说明见 [README](README.md)，源码编译与测试见 [开发指南](CONTRIBUTING.md)。

仓库：<https://github.com/meatRoll/lan-share>。上传前检查 `.gitignore`，不要上传本地临时记录、历史日志、APK、签名密钥、Web 接收文件或本机发现状态。

1. 创建并妥善备份正式 Android 签名密钥，不使用 debug 密钥。三个平台可以使用同一正式密钥。
2. 在 GitHub 仓库 Settings → Secrets and variables → Actions 配置：
   - `ANDROID_KEYSTORE_BASE64`：正式 keystore 的 Base64 内容。
   - `ANDROID_KEYSTORE_PASSWORD`：keystore 密码。
   - `ANDROID_KEY_ALIAS`：签名别名。
   - `ANDROID_KEY_PASSWORD`：密钥密码。
3. 使用 `main` 或 `master` 发布分支；其他分支名需修改工作流。
4. 工作流需获准使用 `contents: write` 创建 Release。Secrets 不应出现在日志或仓库中。

正式签名与 debug 测试签名不同，通常不能直接覆盖安装。切换签名前应确认迁移方式；如需卸载重装，先提示备份设置和私有文件。

## 触发规则

工作流 `.github/workflows/android-release.yml` 在 main/master 的 push 涉及 `gradle.properties` 时启动，对比该 push 前后的属性：

- 仅变更版本的 Android 平台进入构建矩阵。
- 要同时更新该平台三段数字 versionName 与递增的 versionCode。
- 三平台版本均不变（仅修改其他属性）时不构建。
- 首次向新分支推送（before 为全零）构建全部当前平台，建立发布基线。
- 同一个 push 包含多提交时，对比 push 前后，不只比较 HEAD 的父提交。
- Web 没有 Android APK，不参与此矩阵。

工作流也支持在 GitHub Actions 页面使用 `workflow_dispatch` 手动运行。当前实现将手动运行视为没有旧版本基线，会构建所选分支的全部三个平台，即使版本号没有变化。正常发布优先通过版本变更触发 push；手动运行仅用于明确需要的补建或重试，避免为未变更平台重复上传。

每个平台生成独立 GitHub Release，标签例如 `tvModern-v0.5.12`，上传正式签名 APK，同时保存 Actions artifact。用户明确授权的功能测试版本允许在现有数字版本后追加 `_feat.N`（N 为正整数），必须同时增加 versionCode；CI 将这类 Release 标记为预发布，不作为最新版。常规版本继续使用三段数字。重跑同一工作流会复用 Release 并覆盖同名资产。

版本策略见 [AGENTS.md](AGENTS.md)。无需本地 assemble，也不要把 APK 加入 Git。

## 下载构建产物

本机目录 `release-downloads/` 用于下载 GitHub Release / Actions APK，整个目录被 `.gitignore` 忽略。不放源码、签名密钥或配置文件。GitHub 构建成功后下载 APK 到该目录；私有仓库下载需要认证，不把 token 写入脚本或 URL。

## 发布验收

工作流构建成功不代表发布验收完成。发布前按 [正式发布验收清单](RELEASE_CHECKLIST.md) 检查，并在对应版本的发布记录或 PR 中记录验证结果、待确认事项和用户决策；未执行的检查不得标记通过。

当前 CI 用 `-PenableLogs=true` 保留可选诊断能力，运行时首次默认关闭，用户通过“诊断日志”开关启用。关闭时停止网络回调和 TCP 探测；首次未开启时不创建公共日志或注册诊断崩溃处理器。`false` 是彻底禁用能力，并非默认状态。诊断日志的大小限制、导出提示等仍需按发布清单审查。不要在未验收时将版本变更推送至发布分支，因为构建成功后会自动创建 Release。

本地临时笔记不属于发布文档，不得提交；其中长期有效的要求应整理到正式清单，不保留本机地址、过时版本号或临时测试包记录。

本地源码编译与自动测试命令见 [开发指南](CONTRIBUTING.md)，不在此重复维护。
