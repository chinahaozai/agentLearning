// 顶层构建脚本：只声明插件版本，不在这里 apply。
// 版本为撰写时的稳定组合；用新版 Android Studio 打开若提示升级，按提示升即可。
plugins {
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
