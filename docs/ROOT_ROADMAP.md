# Root Hook 后路设计

当前版本不内置 Root 能力。原因是支付 App 的 Root/Xposed 检测、版本混淆和风控会不断变化，不适合放进主 App。保留“后路”的方法是让未来 Root 模块只负责“采集”，主 App 只负责“入库和记账”。

## 推荐结构

```text
LSPosed/Root 模块（未来自建，不在本仓库）
  -> 只读取支付 App 内存中的支付结果
  -> 发送结构化广播

AutoLedger 主 App
  -> RootHookReceiver（默认关闭）
  -> CaptureRouter
  -> PaymentTextParser
  -> LedgerRepository
  -> SQLite
```

主 App 现在已经有广播入口：

```text
Action: com.autoledger.app.action.ROOT_CAPTURE
Package target: com.autoledger.app
```

广播 Extra：

- `package_name`：真实来源包名，例如 `com.tencent.mm`
- `title`：可选标题
- `text`：可包含金额、方向、商户的文本
- `occurred_at`：毫秒时间戳，可选

Root 源在“权限与来源”中默认关闭。打开后，只有同一设备上自建模块发出的广播才会被处理。

## 未来 Root 模块建议

未来若做自用实验，建议把 Root 模块单独打包，不进主 App：

1. 使用 LSPosed 模块挂载到微信/支付宝/云闪付进程。
2. 优先 hook“支付成功回调”或“账单详情对象”，不要 hook 密码输入和加密密钥。
3. 在后台把结果构造成上面广播的 Extra，发给 AutoLedger。
4. 保留原始 App 包名和交易唯一 ID，便于后续对账。
5. 主 App 仍沿用现有 `fingerprint` 去重，避免 Root、通知、无障碍同抓到一笔导致重复流水。

自建实验不要直接扫描所有内存或抓取其他账户数据。只处理：

- 自己手机上本人账号产生的支付/退款/收款结果
- 支付成功后的商户、金额、时间、订单状态
- 用户明确打开 Root Hook 开关后的设备

## 为什么不内置 Root

- 微信/支付宝/云闪付会做 Root、Xposed、Frida 和进程注入检测。
- 支付 App 每月更新，hook 点很容易失效。
- 主 App 若携带 Hook 能力，隐私边界和稳定性都会变差。
- Root 模块更适合作为个人实验，而不是默认运行路径。

## 可以继续扩展但不在第一版

- 官方账单 CSV 导入
- OCR 截图兜底
- 基于商户的自动分类规则
- 银行短信通知
- 定时对账
- 多设备本地备份

