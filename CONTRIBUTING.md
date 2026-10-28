# 开发指南

项目简介和使用方式见 [README](README.md)，版本与维护约束见 [AGENTS.md](AGENTS.md)。Android 发布包由 GitHub Actions 构建，本地仅做源码编译和自动测试，不生成、复制或提交新的 APK/AAB。

## 目录结构

| 路径 | 职责 |
| --- | --- |
| `app/src/main/` | 手机和 TV 共用的 Kotlin 代码、发现与传输服务、HTTP 接口 |
| `app/src/tv/` | 两个 TV 变体共用的 Manifest 与资源 |
| `web/` | 电脑浏览器界面、Node.js 桥接服务与回归测试 |
| `scripts/` | GitHub 发布平台选择及其测试 |
| `gradle.properties` | 各 Android 平台唯一版本配置源 |
| `.github/workflows/android-release.yml` | 正式签名、按平台构建及 GitHub Release 分发 |

`web/tv-log-receiver.js` 是专用诊断接收脚本，不是正常电脑端入口，不作为正式使用流程推荐；诊断清理按 [发布验收清单](RELEASE_CHECKLIST.md) 处理。

## 开发环境

- Android：JDK 17、Gradle 8.9、Android SDK Platform 33 与 Build Tools 33.0.2，配置 `ANDROID_HOME` 或本地 `local.properties` 中的 `sdk.dir`。
- Web：Node.js 18+，使用 Node.js 内置模块，无需 `npm install`。
- 发布矩阵测试：Python 3，无需额外 Python 包。

以上 Android 工具版本与当前 GitHub 工作流保持一致。仓库没有 Gradle Wrapper，命令使用已安装的 `gradle`。首次编译可能需要下载 Gradle 插件和依赖；离线运行需先备齐缓存。

## 本地运行与测试

从仓库根目录启动电脑端：

```sh
node web/bridge.js
```

配置说明见 [Web Desktop 文档](web/README.md)。从仓库根目录运行自动测试：

```sh
python3 scripts/test_release_matrix.py
node web/ui.test.js
node web/receive-tcp.test.js
node web/desktop-send.test.js
node web/discovery.test.js
node web/discovery-networks.test.js
node web/qr.test.js
```

测试分别覆盖版本矩阵、前端传输进度与布局规则、TCP 接收协议及实时进度、Web 已接收文件转发、UDP 发现、CIDR 多网段配置及本地二维码矩阵生成（校验、重叠去重、自身地址排除和取消）。Android 的 `DiscoveryNetworksTest` 可通过 `:app:testPhoneReleaseUnitTest`、`:app:testTvLegacyReleaseUnitTest`、`:app:testTvModernReleaseUnitTest` 执行；CI 在打包前运行对应平台测试。UI 测试不是浏览器截图验收，协议测试也不能代替真实网络、存储和安装器测试；发布前另按正式清单验收。

## Android 源码编译

只编译三平台的 Release Kotlin 源码，不生成 APK：

```sh
gradle :app:compilePhoneReleaseKotlin :app:compileTvLegacyReleaseKotlin :app:compileTvModernReleaseKotlin -PenableLogs=false
```

源码编译不需要正式签名密钥。`-PenableLogs=false` 编译时彻底禁用诊断能力。正式 CI 和可选诊断测试使用 `-PenableLogs=true`，运行时首次仍默认关闭，须用户主动开启；不覆盖版本配置，也不执行本地 `assemble` 或 `bundle` 打包。

## 提交约定

- 按 [版本规范](AGENTS.md) 更新实际受影响平台；文档、Web 或 CI 单独变更不递增 Android 版本。
- 不提交本地临时笔记、日志、接收文件、发现状态、签名材料或构建产物；本地临时笔记的长期有效内容应整理到正式文档。
- 不因关闭诊断而删除正常发现、传输、设备身份与已知地址缓存；不未经用户确认删除历史日志或下载文件。
- 提交前按 [项目维护规范](AGENTS.md) 核对并沿用仓库历史中已确认的提交身份；仅调整仓库级配置，不修改全局身份，身份冲突或无法确认时先询问用户。
- 修改发现、传输或共享代码时运行相应回归测试；在 PR 或发布记录中说明未执行的真机验证，不沿用历史结果。

签名和自动发布步骤集中在 [RELEASING.md](RELEASING.md)，不在此重复维护。
