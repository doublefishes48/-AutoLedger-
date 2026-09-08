# AutoLedger 个人自动记账

一个只给自己或小范围真机使用的 Android 本地记账原型。首版不接云、不加广告、不申请网络权限，数据全部放在手机本地 SQLite。

## 当前能力

- 手动记一笔：金额、收支、分类、商户、账户、备注。
- 通知监听：解析微信、支付宝、云闪付支付/退款类通知。
- 无障碍页面识别：兜底抓取“付款成功”“支付成功”“收款成功”等页面文本。
- 自动入账：置信度达到阈值后直接写入正式流水，低置信度先留在待确认。
- 本地查看：本月收支结余、待确认抓取、最近流水、改分类、删除。
- Root 后路：应用内有独立的 Root Hook 广播入口，默认关闭，不随主 App 启动。
- 隐私边界：无 `INTERNET`，无短信、通讯录权限；原始文本中手机号、卡号先脱敏。

## 当前不做

- 不做微信/支付宝内部接口逆向或内置 Hook 模块。
- 不做云同步、多人账本和云端备份。
- 不做点击支付页、输入密码、自动转账。
- 不作为普通用户发布版本上架。

## 目录

```text
app/src/main/java/com/autoledger/app/
  capture/    来源识别、文本解析、去重入口、Root Hook 广播
  data/       SQLite 数据库和仓库
  service/    通知监听、无障碍监听
  MainActivity.java
docs/ROOT_ROADMAP.md
```

## 构建

建议使用 Android Studio 打开根目录 `AutoLedger`。本机需要：

- JDK 17
- Android SDK Platform 35
- 一台 Android 8.0（API 26）以上真机

命令行构建：

```bash
./gradlew assembleDebug
```

Windows：

```bat
gradlew.bat assembleDebug
```

Debug APK 输出到：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 首次使用

1. 安装 Debug APK。
2. 打开 App，进入“权限与来源”。
3. 点击“去系统开启通知使用权限”，勾选“自动记账”。
4. 点击“去系统开启无障碍”，勾选“自动记账页面识别”。
5. 建议先关闭“高置信度自动入账”，用几笔真实支付检查识别是否准确，再打开自动入账。
6. 如需 Root Hook 后路，安装自建 Hook 模块后再打开“允许未来 Root Hook 广播”。

注意：国产 ROM 可能隐藏通知正文，需要在系统设置里让微信/支付宝/云闪付显示通知详情。无障碍服务不要求 Root，但它只读取当前页可见文本，微信改版或系统隐藏节点时可能需要适配。

## 实现边界

通知监听使用系统提供的 `NotificationListenerService`，只能读取用户主动授权后到达本机的通知。无障碍服务只配置为处理三个包名：

- `com.tencent.mm`
- `com.eg.android.AlipayGphone`
- `com.unionpay` 及云闪付相关子包

服务不会模拟点击、注入手势、读取密码，也不会启动支付流程。抓到的文本会先进 `raw_captures`，再按指纹去重；正式流水独立存放在 `transactions`。

## 相关参考

- [jinyule/bookkeeping](https://github.com/jinyule/bookkeeping)
- [DykiSensei/seamless-bookkeeping](https://github.com/DykiSensei/seamless-bookkeeping)

这两个项目验证了“通知 + 无障碍”实现自动记账是可行的。本项目没有直接复制它们的代码，而是采用了更精简的 Java/XML + SQLite 结构。

