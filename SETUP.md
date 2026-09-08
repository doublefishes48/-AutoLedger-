# 零基础安装与运行指南

这个项目已经写好了源码，你现在不需要学编程。只要装一个 Android Studio，然后用它打开项目，再点运行，就能把 App 装到手机上。

## 你需要准备的

- 一台 Windows 电脑
- 一台 Android 手机
- 一条能传数据的 USB 线

## 第 1 步：安装 Android Studio

用浏览器打开：

```text
https://developer.android.google.cn/studio
```

下载 Windows 版 Android Studio。下载后一直点“下一步/Next”安装，推荐保留默认选项。

Android Studio 会一起安装 JDK 和 Android SDK，所以不需要另外装 Java。

## 第 2 步：首次打开项目

安装完成后打开 Android Studio。第一次启动会有一个向导，选择：

```text
Standard
```

等待它下载 Android SDK 组件。如果下载慢，保持网络稳定，不要关机。

出现欢迎界面后，选择：

```text
Open
```

然后打开这个文件夹：

```text
C:\Users\Xujiaoshou\Documents\Codex\2026-09-07\w\outputs\AutoLedger
```

Android Studio 会自动识别这是一个 Android 工程，并提示加载 Gradle。选择：

```text
Trust Project
```

首次同步可能需要下载一段时间，界面底部会出现进度条。等待完成，不要中途关闭。

## 第 3 步：让手机可以被电脑识别

在手机上打开：

```text
设置 -> 关于手机
```

连续点击“版本号”或“MIUI/HyperOS 版本”约 7 次，直到提示“已进入开发者模式”。

然后打开：

```text
设置 -> 更多设置 -> 开发者选项
```

开启：

```text
USB 调试
```

用 USB 线把手机连到电脑。手机如果弹出“是否允许 USB 调试”，勾选“始终允许”，点确定。

## 第 4 步：把 App 安装到手机

回到 Android Studio，点顶部绿色的“Run”按钮，或者按：

```text
Shift + F10
```

Android Studio 会要求选择设备。选择你的手机，然后等待编译和安装。

## 第 5 步：打开 App 并授权

安装完成后，在手机桌面打开“自动记账”。

在 App 内点：

```text
权限与来源
```

按提示打开：

1. 通知使用权限
2. 无障碍服务

建议先关闭“高置信度自动入账”，用几笔真实支付测试，确认金额和商户识别正确后再打开。

## 常见问题

### 打开项目时提示找不到 Android SDK

选 Android Studio 自己的 SDK 路径，通常是：

```text
C:\Users\你的用户名\AppData\Local\Android\Sdk
```

### 第一次同步很慢

正常。Android Studio 要下载 Gradle 和 Android 编译组件，耐心等即可。

### 手机不显示在设备列表

确认 USB 线支持数据传输，不只是一条充电线。换一条原装线，并重新插拔。

### 国产手机打开通知使用权限后仍抓不到

到手机系统设置里，允许微信、支付宝、云闪付显示通知详情。部分系统默认隐藏通知正文。

### 无障碍识别不到支付成功页

尽量在支付成功页停留一两秒，再退出。部分 App 页面使用自定义画布时，需要后续继续适配。
