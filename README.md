# i18n Helper

面向 JetBrains IDE 的前端国际化辅助插件，提供语言包管理、代码中的翻译预览和翻译编辑功能。

当前版本：`0.1.1`，处于开发阶段。构建平台为 WebStorm 2026.1.4，插件声明兼容平台构建号 `261` 至 `262.*`；其他 IDE 的实际兼容性尚未验证。

## 功能

- 多语言对照表，查看和筛选翻译内容。
- 在 `t('key')` 等调用中显示行内译文及 Hover 翻译预览。
- 编辑单个 Key 的多语言翻译，支持批量删除和前缀替换重命名。
- 检查缺失、未使用的 Key 及翻译质量。
- 配置 Google、DeepL 或 OpenAI 翻译引擎。
- 使用 IDE 设置或项目级 `.i18nrc.yml` 配置语言包与源码扫描范围。

## 使用

1. 在 IDE 设置中打开 **Tools → i18n Helper**，配置语言包目录、路径匹配模式、源语言和展示语言。
2. 从 **Tools** 菜单打开翻译面板，查看语言包并双击 Key 编辑译文。
3. 如需团队共享配置，将根目录的 `.i18nrc.example.yml` 复制到使用插件的前端项目中，重命名为 `.i18nrc.yml`，按实际目录修改。
4. 自动翻译需要网络连接；DeepL 和 OpenAI 需要配置对应服务的 API 密钥。

配置说明见 [YAML 配置指南](docs/YAML_CONFIG.md) 和 [配置示例](.i18nrc.example.yml)。请勿在提交到 Git 的配置中写入真实 API 密钥。

## 开发与打包

安装可运行 Gradle 的 JDK，并设置 `JAVA_HOME`。项目使用 Gradle Wrapper，Kotlin 编译工具链配置为 Java 21。

Windows PowerShell：

```powershell
.\gradlew.bat runIde
.\gradlew.bat buildPlugin
```

macOS / Linux：

```sh
sh ./gradlew runIde
sh ./gradlew buildPlugin
```

默认下载 WebStorm 2026.1.4 作为开发平台，首次运行需要下载依赖。如需复用本机安装的 IDE，可传入目录：

```powershell
.\gradlew.bat buildPlugin "-PlocalPlatformPath=D:/webstrom/WebStorm 2026.1.4"
```

`dev.cmd` 和 `dev.ps1` 是原开发环境的快捷脚本，使用前需按本机情况调整 JDK 路径。其他环境可直接使用 Gradle Wrapper。

打包产物位于 `build/distributions/`。在 IDE 中选择 **Settings → Plugins → 齿轮菜单 → Install Plugin from Disk**，选择生成的 ZIP 安装。

## GitHub 发布

GitHub 仓库用于发布源码；可安装插件需要另外构建 ZIP 并上传至 GitHub Release。

1. 将整理后的源码推送到目标 GitHub 仓库。
2. 完成构建及 IDE 验证后，为对应提交创建版本标签，例如 `v0.1.0`。
3. 创建 GitHub Release，填写变更说明，并上传 `build/distributions/` 中生成的插件 ZIP。

GitHub Release 与 JetBrains Marketplace 是独立的发布渠道；上传 GitHub 不会自动上架 Marketplace。

## 当前限制

- 当前工作区中的修复尚未完成构建及 IDE 实测验证。
- 原生 Key 导航及平台 Shift+F6 重构尚未完整实现。
- 插件提供的重命名操作仅处理已配置的源码目录和后缀；引用解析不完整、文件过大或引用定位存在歧义时会拒绝操作。
- 多文件语言包需正确配置命名空间匹配模式，例如 `{locale}/{namespace}.json`；目标文件归属不明确时不会自动猜测创建。

## 许可证

本项目使用 Apache-2.0 许可证，完整条款见 [LICENSE](LICENSE)。

## 可选的真实项目测试

部分集成测试依赖特定的外部前端项目数据，默认跳过。开发者可通过环境变量 `I18N_TEST_PROJECT` 指定测试项目根目录；这些用例对语言包布局、Key 数量及样例译文有明确断言，普通项目不一定满足。
