# 微信输入法内联填充

一个 Xposed 模块，让 1Password 等密码管理器的填充建议直接显示在微信输入法的候选栏中，点击即可填入账号、密码等信息。

## 功能

- 在候选栏显示自动填充建议，建议较多时可左右滑动查看。
- 输入文字时让出候选栏，正常显示输入法候选词。
- 补充 Android 13 及以上的预测性返回支持。

## 使用条件

- Android 11 或更高版本。
- 支持 libxposed API 102 的 Xposed 框架。
- 微信输入法：已验证 3.5.3 和 3.5.4，其他版本尚未验证。
- 支持键盘内联填充建议的密码管理器，例如 1Password。

## 安装与使用

1. 安装模块 APK，在 Xposed 管理器中启用模块。
2. 勾选推荐作用域：系统框架（`system`）和微信输入法（`com.tencent.wetype`）。
3. 在 Android 设置中选择你使用的自动填充服务，并启用微信输入法。
4. 首次启用后重启设备，在登录等输入框中查看填充建议。

填充建议由密码管理器提供；是否出现建议，也取决于它对当前应用和输入框的支持。模块本身不提供密码管理或短信验证码识别功能。

微信输入法升级后可能需要适配。如果出现问题，反馈时请附上 Android、微信输入法、Xposed 框架和密码管理器的版本，以及相关日志。

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

## 自动构建与发布

- `CI`：推送到 `master` 或提交 Pull Request 时运行测试、Lint 和构建。产物保留 14 天，其中 Release APK 未签名。
- `Release`：手动运行，构建签名 APK，以 `versionName`（例如 `0.2.0`）作为 tag 发布到 GitHub Releases，并附上 R8 mapping 文件。重新运行会替换同名附件。

手动发布前，在仓库的 **Settings → Secrets and variables → Actions** 配置：

| Secret | 内容 |
| --- | --- |
| `KEYSTORE_BASE64` | 现有 Release keystore 文件的 Base64 编码 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `ALIAS` | 签名别名 |
| `KEY_PASSWORD` | 私钥密码 |

使用与本地相同的 keystore，确保发布的 APK 可覆盖安装。在 **Actions → Release → Run workflow** 中选择要发布的分支即可，无需提前打 tag。若同名 tag 已指向其他提交，需要先更新 `versionName`。

## 许可证

[MIT](LICENSE)
