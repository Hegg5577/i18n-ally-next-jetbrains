#!/bin/bash
# i18n Helper for JetBrains - 插件构建脚本

set -e

echo "🔨 开始构建 i18n Helper 插件..."
echo ""

# 清理上次构建
echo "📦 清理上次构建..."
./gradlew clean

# 构建插件
echo ""
echo "⏳ 编译代码和打包插件..."
./gradlew buildPlugin

# 输出位置
BUILD_DIR="build/distributions"
PLUGIN_FILE=$(ls $BUILD_DIR/*.zip 2>/dev/null | head -1)

if [ -n "$PLUGIN_FILE" ]; then
    echo ""
    echo "✅ 构建成功！"
    echo ""
    echo "📁 插件包位置："
    echo "   $PLUGIN_FILE"
    echo ""
    echo "📏 文件大小："
    ls -lh "$PLUGIN_FILE" | awk '{print "   " $5 " - " $9}'
    echo ""
    echo "🚀 安装方法："
    echo "   1. 打开 WebStorm/IDEA"
    echo "   2. Settings/Preferences → Plugins"
    echo "   3. 点击齿轮图标 ⚙️ → Install Plugin from Disk"
    echo "   4. 选择上面的 .zip 文件"
    echo "   5. 重启 IDE"
    echo ""
    echo "✨ 安装完成后，在 Tools 菜单中会出现 i18n Helper 的选项"
else
    echo "❌ 构建失败！"
    exit 1
fi
