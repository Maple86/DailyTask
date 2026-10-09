# DailyTask Relay Cloud Function

`relay` 是 DailyTask 的 CloudBase 云函数，同时服务微信小程序和 Android 设备。

## 云函数职责

- 微信小程序生成设备配对码。
- Android 使用配对码换取设备 Token。
- Android 定时执行 `deviceSync`，获取配置 revision 和待执行指令。
- Android 执行指令后通过 `deviceAck` 回报结果。
- Android 上报通知监听得到的考勤记录。
- 小程序查询设备、发送指令、修改配置和查看考勤。

## 数据库集合

首次部署后，请创建或允许云函数初始化以下集合：

```text
users
devices
pairing_codes
device_configs
commands
attendance_records
audit_logs
```

可以在云函数控制台测试一次：

```json
{
  "action": "initCollections"
}
```

如果 CloudBase 当前环境不允许通过云函数创建集合，请在云开发控制台手动创建。

## 设备 Token

Android 配对成功后，云端只保存 Token 的 SHA-256 哈希。Device Token 只返回一次，请不要写入日志或提交到 Git。

## HTTP 访问

Android 需要能够在公网通过 HTTPS 访问云函数。请优先使用 CloudBase 控制台“HTTP 访问服务”中环境自带的免费默认域名。
默认域名适合开发和测试，但存在访问频率、有效期和稳定性限制；自定义域名是付费套餐能力。为 `relay` 配置路由并得到类似下面的地址：

```text
https://<你的环境访问域名>/relay
```

然后将这个地址填入 Android 的“设置 → 远程中继 → 中继服务地址”。

## 调用示例

### Android 配对

```json
{
  "action": "pairDevice",
  "pairingCode": "482913",
  "deviceName": "备用机",
  "deviceModel": "MI 8",
  "androidVersion": 29,
  "appVersion": "2.5.0",
  "initialConfig": {}
}
```

### Android 同步

```json
{
  "action": "deviceSync",
  "deviceId": "dt_xxx",
  "appVersion": "2.5.0",
  "battery": 68,
  "networkType": "WIFI",
  "taskRunning": true,
  "notificationListenerAlive": true,
  "projectionAlive": false,
  "appliedConfigRevision": 2
}
```

Android 在 HTTP Header 中发送：

```text
Authorization: Bearer <deviceToken>
```

### Android 确认指令

```json
{
  "action": "deviceAck",
  "deviceId": "dt_xxx",
  "commandId": "cloud-document-id",
  "status": "SUCCEEDED",
  "message": "任务已启动"
}
```

### 小程序创建指令

```json
{
  "action": "mpCreateCommand",
  "deviceId": "dt_xxx",
  "type": "START_TASK",
  "idempotencyKey": "START_TASK_1720000000000"
}
```

## 安全建议

- 不要公开设备 Token。
- 数据库权限不要设置为“所有用户可读写”。
- 小程序只通过云函数读写数据。
- 定期撤销不再使用的设备。
- 正式环境建议配置云函数日志脱敏和告警。
