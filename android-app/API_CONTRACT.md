# 已实现的 Demo 后端接口

服务地址填写在 App 的“我的 → 后端服务”，目前部署示例为 `https://api.qianban.cloud/xunyi`。访问令牌只在设备上输入，不写入 APK 或仓库。详细语义见 [后台说明](../backend/README.md)。

| 方法与路径 | 用途 |
| --- | --- |
| `GET /health` | 健康检查，无需令牌 |
| `POST /v1/recordings` | 创建录音元数据，依据地点提及与随附 GPS 自动绑定，或生成高德候选 |
| `GET /v1/recordings/{id}` | 查看地点线索、GPS、绑定依据和候选 |
| `POST /v1/recordings/{id}/places/search` | 按讲述地点查询当前地图候选 |
| `POST /v1/recordings/{id}/places/gps` | 添加同次录音 GPS 并绑定；当前 App 使用示例录音附带的示例位置 |
| `POST /v1/recordings/{id}/places/confirm` | 用户确认服务器返回的候选 ID |
| `DELETE /v1/recordings/{id}` | 删除录音地点数据和关联的共忆会话 |
| `POST /v1/conversations` | 取得参与者同意后开始谈话 |
| `POST /v1/conversations/{id}/turns` | 主动提交长辈或家人的文字 |
| `POST /v1/conversations/{id}/prompts/next` | 返回可忽略的规则演示问题 |
| `DELETE /v1/conversations/{id}` | 删除谈话文字 |

所有 `v1` 接口需要 `Authorization: Bearer <token>`。高德 Key 仅由服务端持有。设备通信、音频上传、语音识别、大模型提问及家庭账号仍等待官方 SDK 和下一阶段身份方案，不把规则演示问题当作模型结果。
