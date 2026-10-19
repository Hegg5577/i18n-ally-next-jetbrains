# i18n Helper - YAML 配置文件

## 概述

`.i18nrc.yml` 是项目根目录的可选配置文件，用于覆盖插件的全局 Settings。每个项目可以有独立的配置，支持团队共享。

配置优先级（高 → 低）：
1. 项目 `.i18nrc.yml` 配置（团队共享，提交到 Git）
2. IDE Settings（`.idea/i18n-helper.xml`，项目本地配置）
3. 插件内置默认值

这样设计让每个项目都能独立配置，即使多个项目在同一 IDE 中也互不影响。

## 配置项

### 路径与定位

| 配置项 | 类型 | 默认值 | 说明 |
|:--------|:------|:--------|:------|
| `localesPath` | string | `src/i18n/locales` | 语言包根目录，相对项目根 |
| `pathMatcher` | string | `{locale}/{file}` | 路径匹配模式，支持 `{locale}` 和 `{namespace}` 占位符 |
| `sourceLocale` | string | `en` | 源语言代码，作为「缺失」判定的基准 |

### 展示与扫描

| 配置项 | 类型 | 默认值 | 说明 |
|:--------|:------|:--------|:------|
| `displayLocales` | array | `[zh]` | 侧边栏优先展示的语言，按列表顺序排列（可在表格表头拖动调整） |
| `supportedExtensions` | array | `[js,jsx,ts,tsx,vue,html,htm]` | 参与扫描的文件后缀 |
| `sourceDirs` | array | `[src]` | 扫描源码的目录（相对项目根），用于「缺失检测」 |
| `keyStyle` | string | `nested` | key 组织形式：`nested`（嵌套）/ `flat`（平铺）/ `auto`（两者兼容） |

### 行内提示与高级选项

| 配置项 | 类型 | 默认值 | 说明 |
|:--------|:------|:--------|:------|
| `inlineAnnotationEnabled` | boolean | `true` | 行内译文提示是否启用 |
| `annotationLocale` | string | `zh` | 行内提示的译文语言 |
| `fallbackToSource` | boolean | `true` | 首选语言缺 key 时是否回退显示源语言原文 |
| `overrideExisting` | boolean | `false` | 批量翻译时是否覆盖已有译文 |
| `namespaceEnabled` | boolean | `false` | 是否启用命名空间（`ns:key` 形式） |
| `namespaceDelimiter` | string | `:` | 命名空间分隔符 |

### 翻译引擎

| 配置项 | 类型 | 默认值 | 说明 |
|:--------|:------|:--------|:------|
| `activeTranslationEngine` | string | `google-free` | 活跃的翻译引擎 ID（`google-free` / `deepl` / `openai`） |
| `deeplApiKey` | string | `` | DeepL API Key（申请地址：https://www.deepl.com/docs-api） |
| `deeplIsFreeApi` | boolean | `true` | DeepL 是否使用免费版 API |
| `openaiApiKey` | string | `` | OpenAI API Key |
| `openaiModel` | string | `gpt-3.5-turbo` | OpenAI 模型名称 |
| `openaiBaseUrl` | string | `https://api.openai.com/v1` | OpenAI API 基础 URL（支持代理或自建 API） |

## 配置作用域

### 全局配置 vs 项目配置

**全局配置** (IDE Settings)
- 位置：`Preferences > Tools > i18n Helper`
- 作用：所有打开的项目的默认配置
- 场景：设置常用的翻译引擎 API Key

**项目配置** (`.i18nrc.yml`)
- 位置：项目根目录
- 作用：仅该项目生效
- 场景：不同项目的翻译文件位置、源语言、显示语言顺序可能不同

### 示例：多项目场景

每个项目可以有完全不同的配置，不相互影响：

```
workspace/
├── project-a/
│   ├── .i18nrc.yml
│   │   localesPath: locales/          ← A 项目翻译在 locales/
│   │   displayLocales: [zh, en, ja]   ← A 项目显示这 3 种语言
│   │   sourceLocale: en
│   └── locales/
│       ├── en.json
│       ├── zh.json
│       └── ja.json
│
└── project-b/
    ├── .i18nrc.yml
    │   localesPath: src/i18n/         ← B 项目翻译在 src/i18n/
    │   displayLocales: [en, fr, de]   ← B 项目显示这 3 种语言
    │   sourceLocale: fr               ← B 项目以法语为源语言
    └── src/i18n/
        ├── fr.json
        ├── en.json
        └── de.json
```

打开 Project A 时，自动加载 A 的配置；打开 Project B 时，自动加载 B 的配置。  
每个项目完全独立，不需要手动切换全局 Settings。

## 示例配置

### 基础配置

```yaml
# 基础路径和语言设置
localesPath: src/lang/locales
sourceLocale: en
pathMatcher: "{locale}/{file}"

# 展示语言（在表格表头可拖动调整顺序）
displayLocales:
  - zh
  - en
  - ja

# 扫描范围
supportedExtensions:
  - js
  - jsx
  - ts
  - tsx
  - vue
```

### 完整配置（含翻译引擎）

```yaml
# 路径
localesPath: src/lang/locales
sourceLocale: en
pathMatcher: "{locale}/{file}"
keyStyle: nested

# 展示
displayLocales:
  - zh
  - en
supportedExtensions:
  - js
  - jsx
  - ts
  - tsx
  - vue

# 行内提示
inlineAnnotationEnabled: true
annotationLocale: zh
fallbackToSource: true

# 翻译引擎配置
activeTranslationEngine: deepl
deeplApiKey: "YOUR_DEEPL_API_KEY"
deeplIsFreeApi: false
```

### 命名空间配置

```yaml
localesPath: src/i18n/locales
sourceLocale: en
pathMatcher: "{namespace}.{locale}.json"  # common.zh.json, common.en.json
keyStyle: nested

# 启用命名空间
namespaceEnabled: true
namespaceDelimiter: ":"
```

### 使用 OpenAI 翻译

```yaml
activeTranslationEngine: openai
openaiApiKey: "sk-..."
openaiModel: "gpt-4"
openaiBaseUrl: "https://api.openai.com/v1"

# 如果使用代理或自建 API
# openaiBaseUrl: "https://proxy.example.com/v1"
```

## 常见问题

### Q: 什么时候应该使用 `.i18nrc.yml`？

A: 当你需要：
- **团队共享配置** — 把 `.i18nrc.yml` 提交到 git，所有成员自动使用相同配置
- **覆盖全局 Settings** — 某个项目有特殊的目录结构或翻译需求
- **版本控制** — 配置变化被 git 追踪，便于团队协作

### Q: `.i18nrc.yml` 和 Settings 配置有什么区别？

A: 
- **Settings** 存储在 `.idea/i18n-helper.xml`，仅对当前 IDE 用户有效，不提交到 git
- **`.i18nrc.yml`** 应该提交到 git，所有团队成员共享，优先级更高

### Q: 修改 `.i18nrc.yml` 后需要重启 IDE 吗？

A: 目前需要重启。未来版本会支持热加载。

### Q: 如何禁用某项功能？

A:
```yaml
# 禁用行内提示
inlineAnnotationEnabled: false

# 禁用翻译引擎
activeTranslationEngine: ""

# 禁用命名空间
namespaceEnabled: false
```

### Q: 多个项目如何使用不同的 `.i18nrc.yml`？

A: 每个项目的根目录放一份 `.i18nrc.yml` 即可。IDE 会在项目打开时自动加载。

## 验证配置

启用 IDE 日志记录可查看配置加载情况：

在 WebStorm/IDEA 的日志窗口（Help → Show Log in Finder）查找包含 `i18nrc.yml` 的日志行：
```
加载 .i18nrc.yml 配置：/path/to/project/.i18nrc.yml
```

如果配置格式错误，日志会显示：
```
解析 .i18nrc.yml 失败：...
```
