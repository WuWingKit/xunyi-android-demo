# 独立研究原型的后端接口

以下接口属于 `backend/` 的单租户研究原型，供后续设备和身份系统接入参考。公开发布的 Android APK 不调用这些接口，也不包含服务地址或访问令牌；它以本机示例数据实现相应的展示和交互。详细语义见 [后台说明](../backend/README.md)。

| 方法与路径 | 用途 |
| --- | --- |
| `GET /health` | 健康检查，无需令牌 |
| `GET /v1/memories` | 记忆列表和录音来源数 |
| `GET /v1/memories/{id}` | 记忆故事、地点与各段讲述来源 |
| `POST /v1/memories/{id}/edit` | 保存用户修改的故事 |
| `DELETE /v1/memories/{id}` | 删除记忆，保留讲述录音 |
| `GET /v1/memories/{id}/map` | 高德地图 PNG |
| `GET /v1/memories/map` | 所有记忆地点的 A–J 标点总览 PNG |
| `POST /v1/memories/{id}/recordings` | 关联另一次录音 |
| `POST /v1/recordings` | 创建录音元数据，依据地点提及与随附 GPS 自动绑定，或生成高德候选 |
| `GET /v1/recordings` | 讲述录音列表与关联记忆数 |
| `GET /v1/recordings/{id}` | 查看地点线索、GPS、绑定依据和候选 |
| `POST /v1/recordings/{id}/places/search` | 按讲述地点查询当前地图候选 |
| `POST /v1/recordings/{id}/places/gps` | 添加同次录音 GPS 并绑定；待真实记忆珠 SDK 接入 |
| `GET /v1/recordings/{id}/places/map` | 已绑定地点或候选总览地图 |
| `GET /v1/recordings/{id}/places/candidates/{candidateId}/map` | 候选地点聚焦地图 |
| `POST /v1/recordings/{id}/places/confirm` | 用户确认服务器返回的候选 ID |
| `DELETE /v1/recordings/{id}` | 删除录音地点数据和关联的共忆会话 |
| `POST /v1/conversations` | 取得参与者同意后开始谈话 |
| `GET /v1/conversations/{id}` | 会话历史轮次 |
| `POST /v1/conversations/{id}/turns` | 主动提交长辈或家人的文字 |
| `POST /v1/conversations/{id}/prompts/next` | 返回可忽略的规则演示问题 |
| `DELETE /v1/conversations/{id}` | 删除谈话文字 |

研究原型的所有 `v1` 接口需要 `Authorization: Bearer <token>`，高德 Key 仅由服务端持有，且该服务不应向公开 APK 提供共享写权限。公开 APK 在本机录音、显示音量和时长，用系统语音合成朗读规则追问；设备通信、音频上传、实时语音识别、大模型提问及家庭账号仍待后续开发，不把规则演示问题当作模型结果。
