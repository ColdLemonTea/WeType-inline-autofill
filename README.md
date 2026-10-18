# WeType Inline Autofill

通过 libxposed API 102 为 WeType（微信输入法）补充 Android Inline Autofill 支持，让 1Password 等自动填充服务可以直接在输入法候选栏中显示建议。

## 功能

- 向系统声明 WeType 支持 Inline Autofill。
- 在 Android 13+ 为未声明的 WeType 版本启用 `OnBackInvokedCallback`。
- 复用微信候选栏的高度、背景和布局，不修改候选数据或 Adapter。
- 普通建议支持横向滚动，首个 pinned 建议占用右侧按钮槽位。
- 正确处理空响应、输入框切换、异步回调、Surface 裁剪和候选栏恢复。
- 支持 libxposed 热重载。

## 要求

- Android 11（API 30）或更高版本。
- 支持 libxposed API 102 的 Xposed 框架。
- 包名为 `com.tencent.wetype` 的兼容版 WeType。
- 已启用支持 Inline Autofill 的系统自动填充服务。

模块的静态作用域为 `system` 和 `com.tencent.wetype`。系统进程 Hook 仅修正能力判断，建议请求、渲染及候选栏 UI 均在 `com.tencent.wetype:hld` 中处理。

## 构建

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug assembleRelease
```

Debug APK 位于 `app/build/outputs/apk/debug/`。Release 默认启用 R8 和资源收缩；提供签名配置时生成 `app-release.apk`，否则生成未签名 APK。

`versionName` 按发布版本维护，`versionCode` 自动取当前 Git 提交数。

本地签名使用不会被提交的 `keystore.properties`：

```properties
storeFile=path/to/release.keystore
storePassword=your-store-password
keyAlias=your-key-alias
keyPassword=your-key-password
```

CI 也可以使用 `SIGNING_KEY`、`KEYSTORE_PASSWORD`、`ALIAS` 和 `KEY_PASSWORD` 环境变量。

## GitHub Actions

- `CI`：推送到 `master` 或提交 Pull Request 时，自动运行单元测试、Lint、Debug 和 R8 Release 构建；APK、R8 mapping 和检查报告保留 14 天。自动 CI 不使用签名密钥，Release APK 为未签名产物。
- `Release`：手动触发，构建所选分支或 tag 的提交，从 Release 构建产物读取 `versionName`，直接作为发布 tag（例如 `0.2.0`）。验证签名后将 `app-release.apk` 和 `mapping.txt` 上传到对应的 GitHub Release；tag 和 Release 不存在时自动创建，重新运行会替换同名附件。

手动发布前，在仓库的 **Settings → Secrets and variables → Actions** 配置：

| Secret | 内容 |
| --- | --- |
| `KEYSTORE_BASE64` | 现有 Release keystore 文件的 Base64 编码 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `ALIAS` | 签名别名 |
| `KEY_PASSWORD` | 私钥密码 |

使用与本地相同的 keystore，确保发布的 APK 可覆盖安装。在 **Actions → Release → Run workflow** 中选择要发布的分支即可，无需提前打 tag。若同名 tag 已指向其他提交，发布会失败，需要先更新 `versionName`。两个 workflow 都拉取完整 Git 历史，保持 `versionCode` 为该提交的 Git 提交数。

## 兼容性

候选栏集成已确认兼容 WeType 3.5.3 和 3.5.4；候选更新方法按参数签名解析，不依赖版本间变化的混淆方法名。WeType 后续升级若建议不再显示，应先重新确认 `WxHldService`、`ImeCandidateView`、getter 和实际 View 层级。

## License

[MIT](LICENSE)
