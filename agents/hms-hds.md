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
