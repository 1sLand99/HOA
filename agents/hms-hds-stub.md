# HDS Stub 实现方案

## 问题背景

HDS 组件（ActionBar 等）在 HAP bytecode 中有两条独立的解析路径，必须同时处理：

| 路径 | 格式 | 机制 | 处理方式 | 状态 |
|------|------|------|----------|------|
| ES module import | `import { HdsActionBar } from '@kit.UIDesignKit'` | `IsNativeModule()` → `requireNapi("hds.hdsBaseComponent")` | 嵌入式 ABC mock（ABC-only 模式） | ✅ |
| ABC record ref | `com.huawei.hmos.hdscomponent/HdsComponent/ets/pages/HdsActionBar` | `LoadJSPandaFile` → record lookup | `ReplaceModuleThroughFeature` 重定向到 `@hms:hds.hdsBaseComponent` | ✅ |

## 整体架构

```
HAP bytecode
  ├─ import { HdsActionBar } from '@kit.UIDesignKit'
  │     → requireNapi("hds.hdsBaseComponent")
  │     → 加载 libhms_hds.so 中嵌入的 hds_mock.abc
  │     → export { HdsActionBar, ActionBarButton, ... }
  │
  └─ record ref: com.huawei.hmos.hdscomponent/HdsComponent/ets/pages/HdsActionBar
        → ReplaceModuleThroughFeature 重定向
        → @hms:hds.hdsBaseComponent
        → 同上的 NAPI 路径
```

## 构建流水线

```
hds_component_mock.js  →  es2abc --module  →  hds_mock.abc  →  llvm-objcopy 嵌入  →  hds_abc.o  →  链接进 libhms_hds.so
```

对应 `plugins/hms/hds/BUILD.gn` 中的三步：
1. `es2abc_gen_abc("gen_hds_abc")` — JS → ABC
2. `gen_js_obj("hds_abc")` — ABC → .o（Android，`llvm-objcopy -I binary`）
3. `ohos_source_set("hms_hds_static")` — 链接 C++ stub + .o → .so

C++ 注册（`hds_base_component_stub.cpp`）使用 `napi_module_with_js` 结构的 ABC-only 模式（`nm_register_func = nullptr`），通过 `nm_get_abc_code` 回调提供嵌入式 ABC。注册双模块名：`hds.hdsBaseComponent` 和 `UIDesignKit`。

---

# 核心经验：ViewV2 mock 实现

## 关键决策：ViewPU (V1) vs ViewV2 (V2)

### 失败的路径：ViewPU

最初 `HdsActionBar` 继承 `ViewPU`，经历了多轮修改（`ObservedPropertyObjectPU` → `SynchedPropertyNesedObjectPU` → `@Observed` 装饰器 → `Reflect.v1` polyfill）均无法使 `isPrimaryIconChanged` 响应式更新生效。

**根因**：SDK 声明 `HdsActionBar` 为 `@ComponentV2`，父组件（`VerticalActionBar`）也是 `@ComponentV2`，使用 `@Local` 状态 + `ObserveV2` 追踪。transpiler 根据 SDK 声明为 V2 子组件生成 V2 专属的 wrapper 代码。ViewPU 走 V1 的 `SynchedProperty` / `@Prop` 体系，与 V2 的 `@Param` / `ObserveV2` 体系不兼容——`updateStateVars` 从未被调用。

### 成功的路径：ViewV2

`HdsActionBar` 改为继承 `ViewV2`，匹配 SDK 声明中的 `@ComponentV2`。V2 parent → V2 child 通信通过 `updateStateVarsOfChildByElmtId` → `updateStateVars` → `updateParam` → `ObserveV2` 污点追踪 → `updateDirtyElements` → 重执行 `observeComponentCreation2` 回调。

**参考代码**：`advanced_ui_component/arcbutton/interfaces/arcbutton.js`（不是 toolbar.js、popup.js 等 V1 组件）。arcbutton.js 是手写 JS 中 `extends ViewV2` 的完整实现。

**注意**：所有 `advanced_ui_component/*/interfaces/*.js` 中，只有 arcbutton.js、dialogv2.js、popupv2.js、toolbarv2.js、segmentbuttonv2.js 等带 "v2" 后缀的文件继承 `ViewV2`。不带 "v2" 的文件（toolbar.js、popup.js、chip.js、filter.js 等）全部继承 `ViewPU`，它们的模式不适用于 `@ComponentV2` 组件。

## V1 vs V2 对照表

| 项目 | V1 (ViewPU) | V2 (ViewV2) |
|------|-------------|-------------|
| 基类 | `extends ViewPU` | `extends ViewV2` |
| 装饰器 polyfill | `Reflect.v1`（ArkUI-X 专有） | `Reflect.decorate`（标准 TC39） |
| 数据类装饰器 | `@Observed`（无 `@Trace`） | `@ObservedV2` + 每个字段 `@Trace` |
| 参数接收 | `new ObservedPropertyObjectPU(params.p, this, 'p')` | `this.initParam("p", params.p)` |
| 参数更新 | `this.__prop.set(value)` | `this.updateParam("p", value)` |
| 参数重置 | 无（V1 通过 `aboutToBeDeleted` 清理） | `this.resetParam("p", value)` in `resetStateVarsOnReuse` |
| 状态初始化 | `setInitiallyProvidedValue(params)` | 构造器中直接赋默认值 |
| 更新入口 | `updateStateVars(params)` → `.set()` / `.reset()` | `updateStateVars(params)` → `updateParam()` |
| 生命周期清理 | `aboutToBeDeleted()` + `purgeVariableDependenciesOnElmtId()` | `resetStateVarsOnReuse()` + `resetMonitorsOnReuse()` |

## ViewV2 最小骨架

```js
var __decorate = (this && this.__decorate) || function (t1, target, key, desc) {
    var c = arguments.length;
    var r = c < 3 ? target : desc === null ? desc = Object.getOwnPropertyDescriptor(target, key) : desc;
    var d;
    if (typeof Reflect === "object" && typeof Reflect.decorate === "function") {
        r = Reflect.decorate(t1, target, key, desc);
    } else {
        for (var u1 = t1.length - 1; u1 >= 0; u1--) {
            if (d = t1[u1]) {
                r = (c < 3 ? d(r) : c > 3 ? d(target, key, r) : d(target, key)) || r;
            }
        }
    }
    return c > 3 && r && Object.defineProperty(target, key, r), r;
};

export class HdsActionBar extends ViewV2 {
    constructor(parent, params, __localStorage, elmtId = -1, paramsLambda, extraInfo) {
        super(parent, elmtId, extraInfo);
        this.initParam("primaryButton", params?.primaryButton);
        this.initParam("actionBarStyle", params?.actionBarStyle);
        // ... 其他 @Param
        this.finalizeConstruction();
    }

    resetStateVarsOnReuse(params) {
        this.resetParam("primaryButton", params?.primaryButton);
        this.resetParam("actionBarStyle", params?.actionBarStyle);
        this.resetMonitorsOnReuse();
    }

    initialRender() {
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            // 在这里直接读 this.actionBarStyle（不要用外部局部变量）
            Column.create();
            Column.width("100%");
        }, Column);
        Column.pop();
    }

    updateStateVars(params) {
        if (params === undefined) { return; }
        if ("actionBarStyle" in params) {
            this.updateParam("actionBarStyle", params.actionBarStyle);
        }
    }

    rerender() { this.updateDirtyElements(); }
}
__decorate([Param], HdsActionBar.prototype, "primaryButton", void 0);
__decorate([Param], HdsActionBar.prototype, "actionBarStyle", void 0);
```

## 关键教训：闭包变量捕获陷阱

**这是 `isPrimaryIconChanged` 问题的第二个根因**（第一个是 V1 vs V2 不匹配）。

在 `observeComponentCreation2` 回调中使用的值，必须在回调**内部**读取 `this.xxx`，不能用外部局部变量捕获：

```js
// ❌ 错误：icon 在回调外计算，re-render 时使用闭包捕获的旧值
_renderButton(btn, isPrimary) {
    const icon = this._resolveIcon(btn, isPrimary);  // 外部计算！
    this.observeComponentCreation2((elmtId, isInitialRender) => {
        Image.create(icon);  // icon 永远是初始值
    }, Image);
}

// ✅ 正确：在回调内部读取 this.actionBarStyle，ObserveV2 能追踪依赖
_renderButton(btn, isPrimary) {
    this.observeComponentCreation2((elmtId, isInitialRender) => {
        const icon = this._resolveIcon(btn, isPrimary);  // 内部计算！
        Image.create(icon);
    }, Image);
}
```

**原理**：V2 的 `updateDirtyElements()` 在 re-render 时，重执行的是 `observeComponentCreation2` 的回调函数。回调内部的 `this.actionBarStyle` 读取会被 ObserveV2 追踪，更新到新值。但回调外的局部变量在 `initialRender()` 执行时就固定在闭包里了，re-render 不会重新计算。

**arcbutton.js 验证**：它在 `observeComponentCreation2` 回调内部直接读 `this.options.status` 和 `this.options.styleMode`，从不使用外部局部变量。

## SymbolGlyph 图标处理

图标资源类型检测：
- `$r('sys.symbol.xxx')` → `type: 40000` → 必须用 `SymbolGlyph.create()`，不能用 `Image.create()`
- 普通资源 → 用 `Image.create()`

因为 baseIcon 和 altIcon 类型相同（都是 symbol 或都不是），可以在回调外用 `btn.baseIcon.type` 预计算 `baseIsSymbol` 来匹配 `observeComponentCreation2` 的第二个参数（组件类型必须一致）。然后在回调内调用 `this._resolveIcon(btn, isPrimary)` 动态解析图标值。

## Button API 兼容性

ArkUI-X 上 `Button.createWithChild` 不可靠（`ButtonType` 枚举缺失、`justifyContent` 无效）。使用 `Row + borderRadius` 模拟圆形按钮，`Row.opacity(0.4)` 表示 disabled 状态。

## ViewV2 是全局类

`ViewV2`、`Param`、`Local`、`ObservedV2`、`Trace`、`Monitor` 由 `stateMgmt.abc` 在运行时加载为全局类，JS mock 中直接使用，无需 import。`es2abc --module` 编译时接受这些未解析的全局引用。

## 注意事项

- **不需要 import 框架类**：`ViewV2`、`Param`、`Trace`、`ObservedV2` 等是 stateMgmt.abc 提供的全局类，直接使用即可。`es2abc --module` 允许未解析的外部引用。
- **不需要 `requireNapi`**：HDS stub 不依赖 `LengthMetrics`、`ColorMetrics` 等运行时工具类（HAP 传入的是原始值或资源 ID，不做自适应计算）。
- **`export default { ... }` 是必须的**：运行时通过 `GetExportObjectFromBuffer("default")` 获取模块导出，缺失会导致所有 named export 不可见。
- **`finalizeConstruction` polyfill** 保留即可，不影响 ViewV2（ViewV2 的 `finalizeConstruction` 在基类 `PUV2ViewBase` 中定义）。

---

# 组件委托策略

| HDS 导出 | 策略 | 说明 |
|----------|------|------|
| `HdsActionBar` | ViewV2 组合 | Row + SymbolGlyph/Image 模拟 |
| `ActionBarButton` | `@ObservedV2` + `@Trace` | V2 数据类，所有字段 `@Trace` |
| `ActionBarStyle` | `@ObservedV2` + `@Trace` | V2 数据类，所有字段 `@Trace` |
| `HdsNavigation` → `Navigation` | 1:1 委托 | ArkUI 全局类 |
| `HdsNavDestination` → `NavDestination` | 1:1 委托 | ArkUI 全局类 |
| `HdsTabs` → `Tabs` | 1:1 委托 | ArkUI 全局类 |
| `HdsListItemCard` → `ListItem` | 1:1 委托 | ArkUI 全局类 |
| `HdsListItem` → `ListItem` | 1:1 委托 | ArkUI 全局类 |
| `HdsTabsController` | `class extends TabsController` | 继承 + 5 个 stub 方法 |
| `HdsSnackBar` | class（no-op stub） | 构造器 + `show`/`dismiss` 空实现 |
| Prefix/Suffix 类（~24 个） | `class extends PrefixItem/SuffixItem` | 数据容器，`this.options = options` |
| Modifier 类 | `class extends XxxAttribute` | `applyNormalAttribute` 空实现 |
| `hdsMaterial` namespace | `export const` 对象 | MaterialType/MaterialLevel 枚举 + `getSystemMaterialTypes()` |

## 枚举（18 个，全部与 SDK 声明一致）

| 枚举 | 值 |
|------|----|
| `ScrollEffectType` | `COMMON_BLUR=0`, `GRADUAL_BLUR=1`, `GRADIENT_BLUR=2`, `IMMERSIVE_GRADIENT_BLUR=3` |
| `HdsNavigationTitleMode` | `FREE=0`, `FULL=1`, `MINI=2`, `MODAL=3` |
| `HdsNavDestinationTitleMode` | `MINI=100`, `MODAL=101` |
| `DividerMode` | `VISIBLE=0`, `NONE=1`, `FOLLOW_SCROLL=2` |
| `DividerShowType` | `OFF=0`, `ON=1`, `AUTO=2` |
| `TextStyleMode` | `NORMAL=200`, `SINGLE_CHARACTER=201` |
| `BottomBuilderShowType` | `DIRECTLY_SHOW=0`, `OVERDRAG_SHOW=1` |
| `HideMode` | `SCROLL_UP_TO=0`, `SCROLL_UP=1`, `SCROLL_DOWN=2`, `SCROLL_UP_TO_BLEND_SCROLL_UP=3` |
| `IconStyleMode` | `SMALL=100`, `NORMAL=101`, `LARGE=102` |
| `BlurStrategy` | `ENABLE=0`, `DISABLE=1`, `ADAPTIVE=2` |
| `TitleSize` | `TITLE_S=0`, `TITLE_ML=1` |
| `IconSize` | `SMALL_ICON=1`, `SYSTEM_ICON=2` |
| `HdsBarStyle` | `COLLAPSE=0`, `EXPAND=1` |
| `HdsTabsBarChangeMode` | `NORMAL=0`, `USER_CLICK=1`, `APP_TRIGGER=2` |
| `HdsAnimationMode` | `SCROLL_ANIMATION=0`, `CLICK_ANIMATION=1` |
| `ExtendBarMode` | `HALF_SCREEN_FIXED=100` |
| `SnackBarOperationType` | `TEXT_ONLY=0` .. `HIGHLIGHT_TEXT_WITH_CLOSE=4` |
| `SnackBarIconType` | `SMALL=0`, `NORMAL=1` |

## Prefix 类（7 个）

`PrefixItem`（空基类）→ `PrefixImage`, `PrefixIcon`, `PrefixBadge`, `PrefixSwitch`, `PrefixToggleButton`, `PrefixButton`, `PrefixCustomBuilder`

## Suffix 类（17 个）

`SuffixItem`（空基类）→ `SuffixText`, `SuffixImage`, `SuffixLoadingProgress`, `SuffixRadio`, `SuffixCheckbox`, `SuffixSwitch`, `SuffixArrow`, `SuffixBadge`, `SuffixButton`, `SuffixIcon`, `SuffixSubIcon`, `SuffixSelect`, `SuffixToggleButton`, `SuffixBadgeAndArrow`, `SuffixTextAndArrow`, `SuffixArrowIconText`, `SuffixCustomBuilder`

---

# 模块解析路径

`ReplaceModuleThroughFeature()`（`module_resolver.cpp`）依次检查：
1. Mock 模块（`IsMockModule`）
2. HMS 模块（`IsHmsModule`）— ArkUI-X 未使用
3. Native module 回退 — 将 `@hms:xxx` 拆解为 `requireNapi("xxx")`

HOA 在步骤 1 前插入 HDS record 重定向：
```cpp
if (requestName.find("com.huawei.hmos.hdscomponent") != CString::npos) {
    requestName = "@hms:hds.hdsBaseComponent";
}
```

随后 `GetStrippedModuleName("@hms:hds.hdsBaseComponent")` → `"hds.hdsBaseComponent"` → `requireNapi("hds.hdsBaseComponent")` → 找到 NAPI stub。

---

# SDK 声明 vs 编译器白名单

HDS 组件名来自两个独立来源：

**来源 A**：HMS SDK 声明文件（`/apps/harmony/sdk/default/hms/ets/api/@hms.hds.*.d.ets`）— 运行时真实存在的组件：
- `HdsNavigation`、`HdsNavDestination`、`HdsActionBar`、`ActionBarButton`、`ActionBarStyle`

**来源 B**：ArkUI-X TypeScript 编译器白名单（`third_party/typescript/src/compiler/ohApi.ts`）— 编译时接受但 SDK 未发布的组件：
- `HdsTabs`、`HdsListItemCard`、`HdsVisualComponent`、`DotMatrix`、`Metaball`、`AudioWave`、`MultiWindowEntryInAPP`

stub 为来源 B 提供了 1:1 委托（`HdsTabs` → `Tabs`、`HdsListItemCard` → `ListItem`），避免未来 HAP 引用时编译通过但运行时崩溃。

---

# 参考文件

| 文件 | 作用 |
|------|------|
| `plugins/hms/hds/src/hds_component_mock.js` | JS mock 源码（ViewV2 + 所有导出） |
| `plugins/hms/hds/hds_base_component_stub.cpp` | C++ 注册（ABC-only，双模块名） |
| `plugins/hms/hds/BUILD.gn` | 三步构建流水线 |
| `advanced_ui_component/arcbutton/interfaces/arcbutton.js` | **ViewV2 手写 JS 参考**（最重要的参考代码） |
| `agents/ets-to-js.md` | ETS → JS transpiler 模式参考 |
| `arkcompiler/ets_runtime/ecmascript/module/module_resolver.cpp:158` | `ReplaceModuleThroughFeature` — HDS HSP record 重定向 |
| `arkcompiler/ets_runtime/ecmascript/base/path_helper.h:86` | `GetStrippedModuleName` — 前缀去除 |
| `foundation/arkui/ace_engine/build/ace_gen_obj.gni` | `gen_js_obj` / `gen_obj` GN 模板 |
| `build/config/components/ets_frontend/es2abc_config.gni` | `es2abc_gen_abc` GN 模板 |
| `/apps/harmony/sdk/default/hms/ets/api/@hms.hds.HdsActionBar.d.ets` | HdsActionBar SDK 声明 |
| `/apps/harmony/sdk/default/hms/ets/api/@hms.hds.hdsBaseComponent.d.ets` | HDS 基础组件 SDK 声明 |
