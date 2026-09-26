# 寻忆 Android Demo

原生 Android Java 演示应用。底部导航为“首页／记忆／共忆／我的”，按照[产品功能文档](https://wcnp29ttq8dx.feishu.cn/wiki/CXJ6wkUwxi8ylLk6KxLcB1gdnUb)与视觉规范实现；旧[HTML 原型](https://github.com/tulip627722-byte/anker-hanker)只提供故事样例和共忆问题参考。

## 演示路径

1. 录音首页显示 1 条待同步录音。点击“同步录音”，可在进度中点“模拟同步失败”，再重试。失败时录音仍在挂件的说明会保留。
2. 同步后打开录音详情。播放器播放内置的**合成演示配音**，有暂停、进度和时长。转写文字可以修改；它与音频分开。
3. 点击“整理成记忆”，修改标题或故事草稿并保存。AI 转写、线索和故事草稿均是预设演示数据，页面明确标注等待官方 SDK。
4. 在记忆页搜索、筛选、查看详情与来源。详情可进入共忆问题、逐条分享、撤回、导出文字或删除。
5. App 预填 HTTPS 服务地址，在“我的”输入单独的访问令牌。录音详情可搜索高德地点候选并由用户确认，或使用示例录音附带的位置记录展示绑定与依据。
6. 在“与家人共忆”取得参与者同意后，主动提交长辈和家人讲述文字。后台返回可忽略的开放式规则问题，并明确标注“等待官方 SDK 下发后进行补充”。
7. 删除或重置演示会请求删除服务器上相应地点与谈话数据，再清除本机演示状态。

本机演示状态保存在应用私有 `SharedPreferences`。配置令牌后，地点与谈话文字会通过 HTTPS 请求后台；内置示例音频不会上传。没有实际蓝牙通信、录音采集、ASR、大模型推理、家庭账号或线上分享。分享选择仅用于本机演示；“导出文字”调用系统分享界面。设备录音入口用状态预览表达，App 不采集手机麦克风。

## 构建与运行

使用 Android Studio 打开本目录。工程配置为 JDK 21、Android SDK 36 和 Android Gradle Plugin 9.0.1。若 Gradle 依赖尚未缓存且 Maven 网络不可用，可用本地 SDK 工具构建：

```powershell
cd E:\WorkSpace\安克黑客松\android-app
.\build-demo.ps1
E:\Android\Sdk\platform-tools\adb.exe install -r .\build-manual\xunyi-demo.apk
```

启动指定模拟器：

```powershell
$env:ANDROID_AVD_HOME = 'E:\Android\Avd'
E:\Android\Sdk\emulator\emulator.exe -avd Pixel_7_API_36
```

构建输出为 `build-manual/xunyi-demo.apk`。脚本首次运行会在忽略目录 `build-manual` 内生成仅供演示的签名密钥。它不会用于正式发布。

## 后端与官方 SDK 接口

“我的”页可保存 HTTPS 服务地址与访问令牌。地点和谈话页调用 [当前接口](API_CONTRACT.md)；其他功能仍是本机演示。官方 SDK 提供后再接入设备通信、音频文件、语音识别和模型提问。保持“原声独立保存、整理需确认、分享逐条授权、线索标注不确定性”的数据边界。
