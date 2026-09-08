# AutoLedger 当前状态

更新时间：2026-09-08

## 已解决

- 支付宝、云闪付通知链路可正常抓取。
- 微信没有稳定支付通知，改为支付成功页截图 OCR。
- 小米澎湃/HyperOS 上 Google Play 动态中文 OCR 模型安装返回 `error=8`，
  因此当前使用 APK 内置中文 OCR 模型，离线可用。
- 内置 OCR 模型已在该真机验证加载成功，日志出现：
  `wechat ocr model ready text=支付成功 12.`，说明中文识别实际可用。
- 微信 OCR 截图前会再次确认微信仍在前台，避免拍到通知栏或桌面。
- 当前 APK 约 48MB，且未申请 `INTERNET` / `ACCESS_NETWORK_STATE`。

## 下次验证

1. 手机保持 USB 调试连接。
2. 确认系统无障碍服务里 AutoLedger 已开启。
3. 用微信做一笔小额支付，成功页停留约 2 秒再退出。
4. 读取日志：

```text
adb shell run-as com.autoledger.app cat files/debug.log
```

重点看：

```text
wechat ocr screenshot
wechat ocr text=...
ingest result=...
```

如果仍无 `wechat ocr screenshot`，说明微信页面没有触发无障碍事件，
下一步要改为监听微信启动页 Activity 或滚动事件后主动识别。
