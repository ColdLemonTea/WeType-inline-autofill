# WeType Inline Autofill

这是一个基于 libxposed API 102 的 Android Xposed 模块，为 WeType（微信输入法）补充系统 Inline Autofill 支持。最低支持 Android 11（API 30）。

## 项目结构

- `app/src/main/java/dev/sawako/wetypeinlineautofill/WeTypeInlineAutofillModule.java`：模块入口、系统与输入法 Hook、InlineSuggestionsRequest 样式及热重载 Hook 替换。
- `app/src/main/java/dev/sawako/wetypeinlineautofill/InlineSuggestionsUi.java`：定位微信候选栏、承载和滚动建议、SurfaceControl 处理以及 UI 状态恢复。
- `app/src/main/resources/META-INF/xposed/`：libxposed 模块声明、入口和静态作用域。
- `app/src/main/res/`：模块名称、简介和 Adaptive Icon 资源。
- `app/src/test/`：与 Android View 无关的建议排序和状态判断单元测试。
- `apks/` 与本地日志仅用于逆向和调试，不得提交。

## 实现链路

1. 在 `system_server` Hook `InputMethodInfo.isInlineSuggestionsEnabled()`，仅对 `com.tencent.wetype` 返回 `true`。
2. Android 13+ 在 WeType 各进程 Hook `ApplicationInfo.isOnBackInvokedCallbackEnabled()`，等价补充 `android:enableOnBackInvokedCallback="true"`。
3. 在 `com.tencent.wetype:hld` Hook `InputMethodService` 的请求与响应回调，并 Hook `WxHldService.onStartInputView()` 处理输入目标切换。
4. 请求使用 AndroidX Autofill v1 样式；响应异步 inflate，首个 pinned 建议占用右侧槽位，其余建议放入横向滚动区。
5. UI 优先复用 `ImeCandidateView` 的原生尺寸、背景和右侧宽度；结构不匹配时才回退到候选栏同级容器。
6. 可滚动 `InlineContentView` 的 SurfaceControl reparent 到裁剪后的 SurfaceView，以避免建议越过右侧图标。
7. generation 标记丢弃过期异步回调；只有至少一个建议渲染完成后才显示外壳。原生候选非空时暂停整条 Inline UI，候选清空后恢复；空响应、切换输入框、detach 和热重载时彻底清理并恢复原候选栏。
8. 热重载前在主线程拆除 UI，重载后按稳定 Hook ID 调用 `replaceHook()`。

## 约束

- 目标包、进程和服务类固定为 `com.tencent.wetype`、`com.tencent.wetype:hld` 和 `com.tencent.wetype.plugin.hld.WxHldService`；`ImeCandidateView` 候选更新入口按唯一的 `(ArrayList, int, boolean, boolean)` 签名解析，以兼容 3.5.3 的 `w()` 和 3.5.4 的 `A()`，后续升级仍需先用 JADX 重新确认。
- 不伪造微信候选数据，不修改候选 Adapter，不依赖目标 APK 的 XML 或资源 ID。
- 优先使用 `ImeCandidateView` 的 getter 和实际 View 层级；反射失败必须安全回退，不能影响输入法原行为。
- 系统进程只修改 Inline Autofill 能力判断；候选 UI 逻辑只能运行在输入法进程。
- Predictive Back opt-in 只在 Android 13+ 的 WeType 进程生效，不修改磁盘上的 APK 或 `ApplicationInfo` 字段。
- 不得在建议尚未成功 inflate 时隐藏原候选栏，也不得在恢复时遗留空槽位、Surface 或修改后的 visibility。
- `restarting=true` 的 `onStartInputView` 不代表新输入目标，不应清空仍有效的建议。
- 保持 Adaptive Icon 的前景、背景及 Android 13+ monochrome 分层。
- `versionName` 按发布版本维护；`versionCode` 必须继续使用当前 Git 提交数，不得改回手工常量。
- Release 签名沿用 `keystore.properties` 或 CI 环境变量配置；不得硬编码、复制或提交密钥与密码。
- 不提交 APK、构建产物、日志、反编译文件或 `local.properties`。

## 验证

使用 Android Studio 自带 JBR：

```powershell
$env:JAVA_HOME='C:\Users\Sawako\scoop\apps\android-studio\2026.1.3.8\jbr'
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug assembleRelease
```

涉及 UI 生命周期时，还需真机验证建议首次出现、输入框切换、输入字符、候选栏恢复、横向滚动裁剪和所有作用域进程的热重载。
