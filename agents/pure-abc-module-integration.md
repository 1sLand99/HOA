# Pure-ABC 模块集成经验

以 `@ohos.fileio` 为例，总结无 .so 的纯 ABC stub 模块的集成流程。

## 背景

某些 HarmonyOS 模块在 ArkUI-X/Android 上没有对应的 native 实现，但 HAP 包会 import 它们。如果模块解析失败，整个 HAP 无法启动。这类模块通过纯 ABC stub（无对应 .so）提供兼容导出。

## 模块加载链

```
import fileio from '@ohos.fileio'
  → IsNativeModule("@ohos:fileio") → requireNapi("fileio")
  → FindNativeModuleByDisk("fileio")
      ├─ 1. dlopen("libfileio.so")                    ← 不存在，跳过
      ├─ 2. dlopen("libfileio_secondary.so")           ← 不存在，跳过
      └─ 3. LoadAbcFromSystemres("fileio.abc")         ← 命中！
          → systemres/abc/fileio.abc 读入内存
          → RegisterByBuffer("fileio", abcBuffer, len)
          → GetExportObjectFromBuffer("default")       ← 获取 default export
```

关键：纯 ABC 模块不注册 .so，运行时回退到 `systemres/abc/` 目录查找 `.abc` 文件。

## 与其他 stub 模式对比

| 模式 | .so | .abc | 注册方式 | 适用场景 |
|------|-----|------|---------|---------|
| **HMS stub** | 有 (`libhms_security.so`) | 嵌入 .so | `napi_module_with_js` + `nm_get_abc_code` | 需要 C++ 逻辑或多模块共享 ABC |
| **Pure-ABC** | 无 | 独立文件 | `systemres/abc/` 自动发现 | 纯 JS 兼容层，无需 native 代码 |
| **Native module** | 有 | 无 | NAPI `NM_modName` 结构体 | 有真实 native 实现 |

选择规则：如果 stub 全是 JS 函数/类（无 C++），用 pure-ABC 最简单。

## 实施步骤

### 1. 创建 JS stub 源文件

位置：`arkui-x/plugins/<category>/<name>/<name>.js`

**关键规则**：使用扁平化 default export 模式：

```js
// 定义所有函数/类（不要用 export function / export class）
function statSync(path) { return new Stat(); }
function stat(path) { return Promise.resolve(new Stat()); }

class Stat {
  constructor() { this.size = 0; this.mode = 0; /* ... */ }
  isDirectory() { return false; }
  isFile() { return false; }
}

// 单一 default export，所有 API 为直接属性
export default {
  statSync,
  stat,
  Stat,
  // ... 所有其他 API
};
```

**为什么必须用 `export default { ... }`？**

运行时通过 `GetExportObjectFromBuffer("default")` 获取模块导出。HAP 中 `import fileio from '@ohos.fileio'` 是 default import。如果 stub 只用 `export function`（named export），default export 为 undefined → `fileio.statSync` 抛 TypeError → `JSON.stringify(error)` 返回 `{}`（Error 属性不可枚举）→ 看到 `FAIL: {}`。

### 2. 添加 BUILD.gn 目标

在 `foundation/arkui/ace_engine/frameworks/bridge/declarative_frontend/BUILD.gn` 中添加：

```gn
ohos_abc("fileio") {
  sources = [ "//plugins/file/fileio/fileio.js" ]
  output_name = "fileio"
  install_images = [ "system" ]
  module_install_dir = "etc/abc/arkui"
  subsystem_name = ace_engine_subsystem   # = "arkui"
  part_name = ace_engine_part             # = "ace_engine_cross"
}
```

关键参数：
- `subsystem_name` + `part_name` 决定输出路径：`out/<build>/arkui/ace_engine_cross/fileio.abc`
- 这与 `sync_arkui_x.sh` 的 B1 步骤匹配：从 `$ARKUI_BUILD/arkui/ace_engine_cross/` 复制 `*.abc`

### 3. 编译 ABC

```bash
# 完整构建路径（需 GN gen + ninja，注意 GN gen 可能有预存冲突）
es2abc --module --output <out_dir>/arkui/ace_engine_cross/fileio.abc \
  plugins/file/fileio/fileio.js
```

`--module` 标志是必须的，生成 module 格式的 ABC。

### 4. 同步到 HOA

```bash
cp <arkui-x-out>/arkui/ace_engine_cross/fileio.abc \
  HOA/app/src/main/assets/sys/systemres/abc/fileio.abc
```

或通过 `sync_arkui_x.sh --abc-only` 自动同步所有 ABC。

### 5. 验证

```bash
# 构建并安装 HOA APK
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk

# 通过 DevTestActivity 加载测试 HAP
adb shell am start -n app.hackeris.hoa/.DevTestActivity \
  --es installHapPath "/data/local/tmp/hap-example-entry.hap" \
  --ez autoLaunch true

# 等待加载后检查 UI
adb shell uiautomator dump /data/local/tmp/ui.xml
adb pull /data/local/tmp/ui.xml /tmp/ui.xml
grep -o 'text="[^"]*"' /tmp/ui.xml | grep fileio

# 查看模块加载日志
adb logcat -d | grep -E "LoadNativeModule|fileio|RegisterByBuffer"
```

## 常见问题

### FAIL: {}

根因是 default export 缺失。确认 stub 使用 `export default { ... }` 而非 `export function` / `export class`。

### 模块未加载

检查：
1. `systemres/abc/<moduleName>.abc` 是否在 APK 的 assets 中
2. 模块名是否与 HAP import 匹配：`@ohos.fileio` → 模块名 `fileio`（去 `@ohos:` 前缀）
3. logcat 中查看 `FindNativeModuleByDisk` 日志

### HAP 推送位置

`/sdcard/` 对很多 app 没有读权限。用 `/data/local/tmp/` 并 `chmod 644`。

## 参考

- HMS stub 文档：[hms-hds-stub.md](hms-hds-stub.md) — 嵌入式 ABC 模式（ABC 嵌入 .so）
- 模块解析：[napi-module-resolution.md](napi-module-resolution.md) — `@ohos:` 前缀模块解析
- 构建系统：[arkui-x-build-system.md](arkui-x-build-system.md) — GN 构建架构
