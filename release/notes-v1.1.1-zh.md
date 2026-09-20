# Maa-phone v1.1.1

修复 v1.1.0 的真机联调问题，尤其是 Assistant Run 页的虚拟屏实时预览。

## 修复

- **Assistant 预览复用 MaaFwApp 模块**：改用 `rememberMovablePreview` /
  `LivePreview` / `FullscreenPreview` / `MaaTouchOverlay`，修复
  SurfaceView 重建后 native preview target 未重新挂上的黑屏问题。
- **补发 Surface**：`MaaPreviewSurface` 在 `setFixedSize` 后若设备没有第二
  次 `surfaceChanged`，主动补一次 `onSurfaceAvailable`。
- **状态不再矛盾**：预览状态由 `runnerBusy || watchdogState == WATCHING`
  决定。run 结束后 MaaFwApp 仍保留虚拟屏/看门狗时，不会在可见画面上盖
  “Not running”。
- **全屏预览可点击/可触控**：增加透明点击层；AssistantActivity 增加
  `configChanges` 并用 `rememberSaveable` 保存全屏状态，横屏请求不会重建
  Activity 后把全屏状态丢掉。全屏内支持手动触点注入和触点轨迹。
- 修复 fresh install 的 `assets/brain/schema.sql` 同步（构建期从根
  `schema.sql` 生成）；DB 仍为 v4。

## 验证

- 真机点击 Assistant 预览可进入全屏，UI dump 可见“退出全屏”。
- Android 单测：466 通过，0 失败，2 skipped；`:app:assembleRelease` 通过。
- Release APK 不内置 DeepSeek key；首次使用在 Assistant → Settings 填入。
