# 寻忆 Demo 后台

Python 3.11 标准库服务，使用 SQLite 保存录音地点证据和共忆文字轮次。服务端持有高德 Web 服务 Key；Android 端只持有单独的 API 访问令牌。

## 接口

除 `GET /health` 外，所有请求需 `Authorization: Bearer <XUNYI_API_TOKEN>`。

| 方法与路径 | 用途 |
| --- | --- |
| `POST /v1/recordings` | 创建录音元数据；有地点提及及同次 GPS 时自动绑定，否则搜索候选 |
| `GET /v1/recordings/{id}` | 查看录音、绑定依据和候选 |
| `POST /v1/recordings/{id}/places/search` | 用高德关键词重新搜索候选 |
| `POST /v1/recordings/{id}/places/gps` | 将录音豆 GPS 样本绑定到已有录音 |
| `POST /v1/recordings/{id}/places/confirm` | 确认本次搜索返回的候选 ID |
| `DELETE /v1/recordings/{id}` | 删除录音地点数据、候选和关联谈话 |
| `POST /v1/conversations` | 在参与者明确同意后开始谈话演示 |
| `POST /v1/conversations/{id}/turns` | 主动提交一轮长辈或家人的文字 |
| `POST /v1/conversations/{id}/prompts/next` | 获取可忽略、可更换的规则演示问题 |
| `DELETE /v1/conversations/{id}` | 删除谈话文字 |

示例请求（用占位符替换令牌）：

```json
{"transcript":"我们在旧电影院见面","placeMention":"旧电影院","gps":{"longitude":121.4737,"latitude":31.2304,"coordinateSystem":"WGS84","sampledAt":"2026-09-26T08:42:00+08:00","source":"demo_sample"}}
```

`source=demo_sample` 会在证据中注明是示例录音附带的位置；正式设备上传时使用 `source=device`。GPS 原坐标保留为 WGS84，供高德地图使用的坐标经其官方转换接口转为 GCJ-02。没有 GPS 时，高德搜索只产生候选；用户提交服务端给出的 `candidateId` 后才绑定。当前 POI 不能证明历史地点，用户需核对后确认。

服务器高德请求限制使用 IPv4，因为当前 Web 服务 Key 的 IP 白名单对应服务器 IPv4；服务地址、令牌和 Key 均不写入仓库。

谈话接口只处理用户主动提交的文字，不接收或监听实时音频。返回的提问有 `source=demo_rules` 和“等待官方 SDK 下发后进行补充”标识，后续可替换成官方 SDK 的语音识别、说话人分离与大模型能力。

## 运行

环境变量：`XUNYI_API_TOKEN`、`AMAP_KEY`、`XUNYI_DB`、`XUNYI_HOST`、`XUNYI_PORT`。密钥放在服务器 `/etc/xunyi/xunyi.env`，不写进 Git。

```bash
python3 -m unittest discover -s tests -v
XUNYI_API_TOKEN=<独立长随机令牌> AMAP_KEY=<服务器白名单Key> XUNYI_DB=/tmp/xunyi.db python3 server.py
```

部署模板在 `deploy/`。正式环境服务只监听 `127.0.0.1:8765`，由 Nginx 的 HTTPS 路径代理。后台当前是黑客松 Demo 的单租户数据模型；正式家庭账号、硬件身份、音频存储与权限隔离需在官方设备协议和身份系统确定后补齐。
