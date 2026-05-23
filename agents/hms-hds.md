# HMS HDS 模块适配方案

## 问题背景

Dashboard HAP (`top.rayawa.dashboard`) 启动时导入 `@hms:hds.hdsBaseComponent`，报错：
```
SyntaxError: does not provide export name 'HdsNavigation'
```

## HDS 是什么

HMS（华为移动服务）中的 HDS（Huawei Design System）= 华为设计系统。`@hms:hds.hdsBaseComponent` 是一个 UI 组件库，提供：

- **HdsNavigation** — Navigation 的华为设计规范包装，属性与标准 Navigation 几乎一致（titleBar、navDestination、mode 等）
- **HdsNavDestination** — NavDestination 的包装，同样属性基本对齐
- 相关枚举：`HdsNavigationTitleMode`（FREE/FULL/MINI）、`ScrollEffectType`（COMMON_BLUR）
- 声明位于 SDK：`/apps/harmony/sdk/default/hms/ets/api/@hms.hds.hdsBaseComponent.d.ets`（约 940 行）
- syscap: `SystemCapability.UIDesign.HDSComponent.Core`，自 API 18（5.1.0）起

## OHOS 原生系统的解析流程

1. **配置文件**：设备上存在 `/system/etc/system_kits_config.json`，格式如：
   ```json
   {
     "systemkits": [
       {
         "namespace": "@hms:hds.hdsBaseComponent",
         "targetohm": "实际HSP模块路径",
         "sinceVersion": 18
       }
     ]
   }
   ```

2. **运行时注册**：`js_runtime.cpp:769-770` 在 VM 启动时读取配置，调用 `JSNApi::SetHmsModuleList(vm, systemKitsMap)` 注册到 EcmaVM 的 `hmsModuleList_` 映射表

3. **模块解析**：当 ETS 代码 `import { HdsNavigation } from '@hms:hds.hdsBaseComponent'` 时：
   - `module_path_helper.h` 中 `ChangeTag()` 将 `@hms.` 规范化为 `@hms:`
   - `module_resolver.cpp` 中 `ReplaceModuleThroughFeature()` 查询映射表
   - 找到后将请求路径替换为 `targetohm`（实际 HSP 包路径），加载对应 ABC 模块

## ArkUI-X 缺失了什么

**整条链路都不存在：**

- ArkUI-X 的 `js_runtime.cpp` **从未调用** `SetHmsModuleList`
- 没有 `system_kits_config.json` 配置文件
- 没有 HDS 组件的 ABC 实现模块
- 结果：所有 `@hms:` 前缀的导入在 ArkUI-X 上必然失败

## ETS Runtime 中已有的三条解析路径

ets_runtime 代码中 `ReplaceModuleThroughFeature()` 依次检查：

1. **Mock 模块**（`IsMockModule`）— 可将任意模块重定向到另一个模块
2. **HMS 模块**（`IsHmsModule`）— 通过 `system_kits_config.json` 映射
3. **requireNapi 回退**（interop 层）— 将 `@hms:xxx` 拆解为 `requireNapi("xxx")`

## 解决方案对比

| 方案 | 原理 | 工作量 | 可行性 |
|------|------|--------|--------|
| **Stub ABC** | 写 ETS 文件把 Navigation/NavDestination 重新导出为 HdsNavigation/HdsNavDestination，编译成 ABC，通过 system_kits_config 注册 | 中 | 最正规，但需要 ETS→ABC 编译工具链 |
| **Mock 模块** | 利用 `IsMockModule` 机制，将 `@hms:hds.hdsBaseComponent` 指向一个内置的 stub 模块 | 小 | 最轻量，不需要额外编译 |
| **NAPI 原生模块** | 注册 `hds.hdsBaseComponent` 为 NAPI 模块，用 C++ 导出 stub 组件 | 大 | 组件是 UI 元素，NAPI 不太适合 |

## 核心难点

HdsNavigation/HdsNavDestination 是 UI 组件（`@Component`），不是普通函数或类。即使做 stub，也需要在 ArkUI 的组件系统中注册它们。

好消息是 ets-loader 已经有对应的组件 JSON 定义（`hdsnavigation.json`、`hdsnavdestination.json`），属性列表与标准 Navigation/NavDestination 高度重叠，说明底层大概率是复用同一套 C++ 渲染实现。

## 当前策略

**优先尝试 Mock 模块方案**，用最小代价让 `@hms:hds.hdsBaseComponent` 可解析。

## 关键文件

- 类型声明: `/apps/harmony/sdk/default/hms/ets/api/@hms.hds.hdsBaseComponent.d.ets`
- 组件定义: `/apps/harmony/sdk/default/openharmony/ets/build-tools/ets-loader/components/hdsnavigation.json`
- 模块解析: `/data/share/hoa2/arkui-x/arkcompiler/ets_runtime/ecmascript/module/module_resolver.cpp:139`
- OHOS 注册: `/src/ohos/foundation/ability/ability_runtime/frameworks/native/runtime/js_runtime.cpp:769`
- ArkUI-X runtime: `/data/share/hoa2/arkui-x/foundation/appframework/ability/ability_runtime/cross_platform/frameworks/native/jsruntime/src/js_runtime.cpp`

---

## HDS 组件清单——两个来源

### 来源 A：HMS SDK 声明文件（权威，运行时真实存在）

文件：`/apps/harmony/sdk/default/hms/ets/api/@hms.hds.hdsBaseComponent.d.ets`
版本：`@since 5.1.0(18)`，Kit: `UIDesignKit`

**SDK 实际导出的 UI 组件（`export const`）：**

| 组件 | 本质 | ArkUI 对应 |
|------|------|------------|
| `HdsNavigation` | Navigation 的 HDS 设计规范包装 | `Navigation` |
| `HdsNavDestination` | NavDestination 的 HDS 设计规范包装 | `NavDestination` |
| `HdsNavigationInstance` | HdsNavigation 组件实例 | — |
| `HdsNavDestinationInstance` | HdsNavDestination 组件实例 | — |

附加：枚举 2 个、类型别名 5 个、接口 16 个、属性类 2 个。总计 30 个导出。

独立模块：
- `@hms.hds.hdsDrawable` — 6 个 HDS 图标处理函数
- `@hms.hds.analogclock` — 虚拟模块（无声明文件，仅 `AnalogClockAttribute`/`AnalogClockOnHourCallback` 两个类型引用）

### 来源 B：ArkUI-X 编译器扩展白名单（编译时接受，运行时未必存在）

文件：`third_party/typescript/src/compiler/ohApi.ts:1541-1555`

这是 ArkUI-X **对 TypeScript 编译器的补丁**。它定义了 `extendComponentWhiteList`，
让 ETS→ABC 编译器在编译阶段接受这些组件名（否则会报"未知组件"错误）。

**白名单中比 SDK 声明文件多出的组件名：**

| 组件 | ArkUI 对应 | SDK 状态 |
|------|------------|----------|
| `HdsTabs` | `Tabs` | SDK 未发布 |
| `HdsListItemCard` | `ListItem` | SDK 未发布 |
| `HdsVisualComponent` | — | SDK 未发布 |
| `DotMatrix` | — | SDK 未发布 |
| `Metaball` | — | SDK 未发布 |
| `AudioWave` | — | SDK 未发布 |
| `MultiWindowEntryInAPP` | — | SDK 未发布 |

**关键认识**：编译器和 SDK 是两个独立系统，编译器的白名单比 SDK 声明文件更宽泛。
如果未来有 HAP 在编译时使用了这些名称（编译器不会报错），但运行时找不到实现，
我们的 stub 需要至少提供一个不崩溃的占位。

### 我们的策略

- **SDK 已发布的组件**（`HdsNavigation`、`HdsNavDestination`）→ 委托至标准 ArkUI 内置组件
- **编译器白名单但 SDK 未发布的组件**（`HdsTabs`、`HdsListItemCard`）→ 预委托至对应内置组件
- **无对应 ArkUI 组件的白名单项**（`DotMatrix` 等）→ 暂未实现，后续可做空桩占位
- **Instance / Attribute 类** → 返回 `undefined` 的桩函数，框架对此容忍度尚可

## 当前实现状态

文件：`plugins/hms/hds/hds_base_component_stub.cpp`
模块名：`hds.hdsBaseComponent`

### 委托组件（napi_get_named_property from global）

| 导出 | 委托至 | 状态 |
|------|--------|------|
| `HdsNavigation` | global `Navigation` | ✅ 已实现 |
| `HdsNavDestination` | global `NavDestination` | ✅ 已实现 |
| `HdsTabs` | global `Tabs` | ✅ 已实现（预委托） |
| `HdsListItemCard` | global `ListItem` | ✅ 已实现（预委托） |

### 枚举

| 导出 | 值 | 状态 |
|------|-----|------|
| `ScrollEffectType` | `{ COMMON_BLUR: 0 }` | ✅ 已实现 |
| `HdsNavigationTitleMode` | `{ FREE: 0, FULL: 1, MINI: 2 }` | ✅ 已实现 |

### 桩函数（返回 undefined）

| 导出 | 状态 |
|------|------|
| `HdsNavigationInstance` | ⚠️ 桩 |
| `HdsNavDestinationInstance` | ⚠️ 桩 |
| `HdsNavigationAttribute` | ⚠️ 桩 |
| `HdsNavDestinationAttribute` | ⚠️ 桩 |
| `HdsTabsInstance` | ⚠️ 桩 |
| `HdsTabsAttribute` | ⚠️ 桩 |
| `HdsListItemCardInstance` | ⚠️ 桩 |
| `HdsListItemCardAttribute` | ⚠️ 桩 |

### SDK 类型/接口（不需要运行时实现）

30 个导出中有 23 个是类型声明（type alias + interface + enum）——这些只在编译期存在，
HAP 的 ABC 字节码中不包含它们，运行时无需实现。

 |

### 编译器常量

`developtools/ace_ets2bundle/compiler/src/pre_define.ts:715-716`：
```typescript
export const HDSNAVIGATION: string = 'HdsNavigation';
export const HDSNAVDESTINATION: string = 'HdsNavDestination';
```
编译器只对这两个 HDS 组件名做了特殊处理（在 `process_component_build.ts` 中 `equalToHiddenNav` / `equalToHiddenNavDes` 判断）。

---

## 当前实现状态

文件：`plugins/hms/hds/hds_base_component_stub.cpp`
模块名：`hds.hdsBaseComponent`

| 导出 | 状态 | 说明 |
|------|------|------|
| `ScrollEffectType` | ✅ | 枚举 `{ COMMON_BLUR: 0 }` |
| `HdsNavigationTitleMode` | ✅ | 枚举 `{ FREE: 0, FULL: 1, MINI: 2 }` |
| `HdsNavigation` | ✅ | `napi_get_named_property(global, "Navigation")` 委托至内置组件 |
| `HdsNavDestination` | ✅ | `napi_get_named_property(global, "NavDestination")` 委托至内置组件 |
| `HdsNavigationInstance` | ⚠️ 桩 | 返回 `undefined` |
| `HdsNavDestinationInstance` | ⚠️ 桩 | 返回 `undefined` |
| `HdsNavigationAttribute` | ⚠️ 桩 | 返回 `undefined` |
| `HdsNavDestinationAttribute` | ⚠️ 桩 | 返回 `undefined` |
| `HdsTabs` | ❌ 未实现 | — |
| `HdsListItemCard` | ❌ 未实现 | — |
| `HdsVisualComponent` | ❌ 未实现 | — |
| `DotMatrix` | ❌ 未实现 | — |
| `Metaball` | ❌ 未实现 | — |
| `AudioWave` | ❌ 未实现 | — |
| `MultiWindowEntryInAPP` | ❌ 未实现 | — |
| 独立模块（5个） | ❌ 未实现 | 均无 NAPI 模块注册 |

---

## 替代策略（按难度分级）

### 一级：1:1 委托（低难度）

| 组件 | 委托目标 | 方法 |
|------|----------|------|
| `HdsNavigation` | `Navigation` | ✅ 已实现 |
| `HdsNavDestination` | `NavDestination` | ✅ 已实现 |
| `HdsTabs` | `Tabs` | `napi_get_named_property(global, "Tabs")` |
| `HdsListItemCard` | `ListItem` | `napi_get_named_property(global, "ListItem")` |

这些组件是标准 ArkUI 组件的 HDS 设计规范包装，属性集高度重叠，
委托后功能可正常运行（缺失 HDS 视觉风格，但不影响交互逻辑）。

### 二级：组合实现（中难度）

| 组件 | 替代方案 |
|------|----------|
| `HdsVisualComponent` | 返回 `Stack` + `Column` 组合（视觉效果容器桩） |

HDS VisualComponent 没有单一 ArkUI 对应组件，但作为容器类组件，
返回一个基础容器构造器（Stack/Column）可保证 import 和布局不崩溃。

### 三级：空桩占位（高难度）

| 组件 | 原因 | 替代方案 |
|------|------|----------|
| `DotMatrix` | ArkUI-X 无对应实现 | 返回空的 Column 构造器，页面不白屏 |
| `Metaball` | ArkUI-X 无对应实现 | 同上 |
| `AudioWave` | ArkUI-X 无对应实现 | 同上 |
| `MultiWindowEntryInAPP` | Android 多窗口机制不同 | 同上 |

这些是 HMOS 独有的特效/功能组件，在 ArkUI-X 上没有等价实现。
空桩的目标是：让 `import` 不报错、组件构造不抛异常、页面能渲染出其他内容。

### Attribute / Instance 辅助类

当前返回 `undefined` 但日历 HAP 已验证不影响渲染。
如有 HAP 因 `undefined is not callable` 报错，可将桩函数改为返回空对象 `{}`。

---

## 相关仓库修改

| 仓库 | 分支 | 文件 | 状态 |
|------|------|------|------|
| `plugins/` | `hoa-weekly` | `hms/hds/BUILD.gn`（新增） | 已提交 |
| | | `hms/hds/hds_base_component_stub.cpp`（新增） | 已提交 |
| | | `plugin_lib.gni`（修改） | 已提交 |
| `build_plugins/` | `hoa-weekly` | `sdk/arkui_cross_sdk_description_std.json`（修改） | 已提交已 push |
| `.repo/manifests/` | `hoa-weekly` | `hoa-weekly.xml`（修改） | 已提交 |
| `.repo/local_manifests/` | N/A | `hoa.xml`（新增） | 仅本地 |
| `HOA/` | `dev` | `HoaApplication.kt`（修改） | 已提交 |
