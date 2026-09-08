# 自动记账当前问题与明日测试

更新时间：2026-09-08

## 已确认的结论

- 微信、支付宝、云闪付都存在。App 已编译并安装到手机。
- 通知使用权限已开启。
- 无障碍页面识别已开启。
- 系统为澎湃 4。它把 AutoLedger 列为“允许通知监听”，但不会总把服务真正绑定到运行状态。
- 通过 `cmd notification disallow_listener` + `allow_listener` 可强制系统绑定通知监听服务。
- 已加入后台保活服务和本地调试日志，用来绕过澎湃吞 logcat 的问题。

## 当前真正未解决的点

- AutoLedger 数据库目前仍为 0 条流水。
- 最近一次支付宝测试发生在旧版本且监听服务未运行时，不能证明“支付宝通知解析失败”。
- 微信支付可能不弹系统通知，也可能支付成功页不暴露无障碍节点。
- 截图 OCR 还未实现。
- 自动分类目前只有内置关键词规则，还没有用户自定义规则界面。
- 本地 SQLite 表已经建立，当前是几 KB，不需要担心几万条数据占用问题。

## 另一个 AI 总结里需要纠正的地方

“支付宝、云闪付正常”目前并不成立。系统日志显示支付宝通知发出过，但澎湃 4 没有把通知送到 AutoLedger 的监听服务。这是当前首要问题，不是微信特有的问题。

“无障碍节点失败后用截屏 OCR 解决微信”可以作为下一步方案，但目前还没验证微信真实页面结构。先用 `uiautomator dump` 看微信支付成功页是否真的没有文本节点，再决定是否加 OCR。

## 明日测试流程

1. USB 连接手机，允许 USB 调试。
2. 先执行一次通知监听器重新绑定：

```text
adb shell cmd notification disallow_listener com.autoledger.app/.service.NotificationCaptureService
adb shell cmd notification allow_listener com.autoledger.app/.service.NotificationCaptureService
```

3. 打开 App，确认后台保活通知存在。
4. 做一笔真实支付宝小额支付，成功页停留 2 秒。
5. 读取 AutoLedger 本地调试日志：

```text
adb shell run-as com.autoledger.app cat files/debug.log
```

如果日志显示 `notification posted pkg=com.eg.android.AlipayGphone`，说明已经送到，问题在金额/方向解析。
如果完全没有支付宝日志，说明澎湃仍拦截通知，需要进一步处理系统保活或改用页面识别。

## 微信下一步

- 做一笔微信小额支付，停留支付成功页。
- 使用 `adb shell uiautomator dump /sdcard/ui.xml` 查看有没有金额和商户文本。
- 如果能看到文本，就继续增强无障碍解析。
- 如果看不到文本，再加入本地截屏 OCR，只对微信支付结果页使用。

