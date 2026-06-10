# Pure-ABC 模块构建集成

以 `@ohos.settings`、`@ohos.fileio`、`@ohos.file.environment` 为例，总结无 .so 的纯 ABC stub 模块从手动编译到 GN 构建体系的集成过程。

## Pure-ABC 模式

无原生代码（无 .so），纯 JS 实现，编译为独立 `.abc` 文件。运行时通过模块回退链加载：

```
import settings from '@ohos.settings'
  → IsNativeModule("@ohos:settings") → requireNapi("settings")
  → FindNativeModuleByDisk("settings")
      ├─ 1. dlopen("libsettings.so")           ← 不存在，跳过
      ├─ 2. dlopen("libsettings_secondary.so") ← 不存在，跳过
      └─ 3. LoadAbcFromSystemres("settings.abc") ← 命中！
          → RegisterByBuffer → GetExportObjectFromBuffer("default")
```

与其他模式对比：

| 模式 | .so | .abc | 注册方式 | 适用 |
|------|-----|------|---------|------|
| **Pure-ABC** | 无 | 独立文件 | `systemres/abc/` 自动发现 | 纯 JS，无 native 代码 |
| HMS stub | 有 | 嵌入 .so | `napi_module_with_js` + `nm_get_abc_code` | 需要 C++，或多模块共享 ABC |
| Native module | 有 | 无 | NAPI `NM_modName` | 有真实 native 实现 |

## JS stub 写法

**必须用扁平化 `export default { ... }`**。运行时通过 `GetExportObjectFromBuffer("default")` 获取，HAP 中 `import xxx from '@ohos.xxx'` 是 default import。

```js
function getValueSync(context, name, defValue) {
  if (name in store) return store[name];
  return defValue !== undefined ? defValue : '';
}

export default {
  getValueSync: getValueSync,
  setValueSync: setValueSync,
  // 常量命名空间
  display: { FONT_SCALE: 'FONT_SCALE', ... },
  domainName: { DEVICE_SHARED: 'DEVICE_SHARED', ... },
};
```

> `export function` / `export class`（named export）导致 default export 为 undefined → `TypeError: xxx is not a function` → `JSON.stringify(error)` 返回 `{}`（Error 属性不可枚举），看到 `FAIL: {}`。

## 构建集成历程

### 阶段 0：手动编译 (2026-06-03)

- `fileio.abc`/`environment.abc`：手动 `es2abc` 编译，产物直接放入 APK assets
- `settings`：C stub（`settings_stub.c` 210 行）→ `libsettings_napi.so` → `System.loadLibrary` 预加载
- `sync_arkui_x.sh` B0 段：每次构建时 `es2abc` 手动编译 `jsSettings.js`

**问题**：不经过 GN，源码修改后 .abc 不会自动重建；settings 仍在用 C stub。

### 阶段 1：融入 GN 构建 (2026-06-10)

三个模块在 `ace_engine/frameworks/bridge/declarative_frontend/BUILD.gn` 中添加 `ohos_abc` 目标，并加入 `declarative_js_engine` 的 deps 链。

```gn
ohos_abc("settings") {
  sources = [ "engine/jsSettings.js" ]
  output_name = "settings"
  ...
}
```

**发现**：GN 只构建可达目标。`ohos_abc()` 定义了目标但未出现在任何 `deps` 中 → ninja 不会编译。对比正常工作的 `:withenv`，它在 `declarative_js_engine` 和 `declarative_js_engine_ng` 两处 deps 中都被引用。

修复：`deps += [ ":settings", ":fileio", ":environment" ]`。

清理：
- 删除 `libsettings_napi.so`（旧 C stub 产物）
- 删除 `HoaApplication.kt` 中 `System.loadLibrary("settings_napi")`
- 删除 `sync_arkui_x.sh` 中的 es2abc fallback 和 C stub 构建

### 阶段 2：迁移到 plugins (2026-06-10)

**动机**：上游 ace_engine 的 `ohos_abc` 目标全是内部框架模块（`statemanagement`、`shape`、`uicontext` 等），HOA 新增的三个 `@ohos.*` 属于应用层 API，应与其他 70+ 插件同放在 `plugins/`。

```gn
# plugins/settings/BUILD.gn（新建）
import("//build/templates/abc/ohos_abc.gni")
ohos_abc("settings") {
  sources = [ "settings.js" ]
  output_name = "settings"
  install_images = [ "system" ]
  module_install_dir = "etc/abc/arkui"
  subsystem_name = "plugins"
  part_name = "settings"
}
```

ace_engine deps 改为跨目录引用：
```diff
-      ":settings",
-      ":fileio",
-      ":environment",
+      "//plugins/settings:settings",
+      "//plugins/file/fileio:fileio",
+      "//plugins/file/environment:environment",
```

`jsSettings.js` 从 ace_engine 移到 `plugins/settings/settings.js`。

输出路径变化：
| 迁移前 | 迁移后 |
|--------|--------|
| `ace_engine_cross/settings.abc` | `plugins/settings/settings.abc` |
| `ace_engine_cross/fileio.abc` | `plugins/file/fileio.abc` |
| `ace_engine_cross/environment.abc` | `plugins/file/environment.abc` |

`sync_arkui_x.sh` 新增 B1.1 段从 `plugins/settings/` 和 `plugins/file/` 复制。

## 当前架构

```
plugins/                              ace_engine/
├── settings/                         └── declarative_frontend/
│   ├── BUILD.gn   ohos_abc              └── BUILD.gn
│   └── settings.js                          ohos_abc("statemanagement")  ← 框架内部
├── file/                                     ohos_abc("withenv")          ← 框架内部
│   ├── fileio/                               deps += [
│   │   ├── BUILD.gn   ohos_abc                 "//plugins/settings:settings",     ← 跨目录
│   │   └── fileio.js                           "//plugins/file/fileio:fileio",
│   ├── environment/                            "//plugins/file/environment:environment",
│   │   ├── BUILD.gn   ohos_abc               ]
│   │   └── environment.js
│   └── fs/              ← 原生 .so (plugin_lib)
└── ... 70+ 其他插件
```

- **ace_engine** 的 `ohos_abc` 目标：框架内部模块（由上游 ArkUI-X 定义，HOA 仅添加 `withenv`）
- **plugins** 的 Pure-ABC 目标：`@ohos.*` 应用层 API（由 HOA 添加）
- `plugins/plugin_lib.gni` 仍假设产物为 `.so`，Pure-ABC 模块使用独立的 `ohos_abc.gni`

## 新增 Pure-ABC 模块流程

1. 在 `plugins/<category>/<name>/` 下创建 `name.js` 和 `BUILD.gn`
2. `BUILD.gn` 导入 `ohos_abc.gni`，定义 `ohos_abc` 目标
3. 在 `ace_engine/.../BUILD.gn` 的两处 deps 中添加 `//plugins/<category>/<name>:<name>`
4. 在 `sync_arkui_x.sh` B1.1 的 `PLUGIN_ABC_DIRS` 中添加输出路径
5. 构建验证：`build.sh` → `sync_arkui_x.sh` → `gradlew assembleDebug`
6. 验证加载链：`adb logcat | grep "FindNativeModuleByDisk.*<name>"`
