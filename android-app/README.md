# 寻忆 Android Demo

原生 Android Java 应用，按暖奶油色、清晰文字、大触控区域设计。底部为“首页／记忆／共忆／我的”。开发顺序和问题清单见[详细检查与设计](../DESIGN_REVIEW_2026-09-27.md)。

## 演示路径

1. 首页直接看到家庭记忆，设备卡显示当前连接状态。
2. 记忆页搜索 3 条服务端保存的示例记忆。第一条由 2 段独立讲述组成，可分别播放；示例讲述使用本地演示配音。首页也可进入录音收件箱，逐段查看讲述与关联记忆。
3. 记忆页的“打开记忆地图”展示全部记忆地点及 A/B/C 故事对应项；详情地图展示单条记忆的位置。点“核对录音中的地点”后自动加载高德地点候选，在地图下方点 A–E 或点击候选卡切换，再确认绑定。确认后关联记忆地点也会更新。
4. 记忆详情可直接修改服务端故事、调用系统分享面板分享文字，或删除整理结果而保留原始讲述。
5. 共忆页以语音为主要入口。参与者同意后点“开始说话”，手机麦克风录制本轮讲述，同时显示时长和音量；结束后核对演示转写并生成开放追问。服务器保存文字轮次，手机朗读追问；本轮声音可回听。文字补充是次要入口。实时语音识别及大模型能力等待官方 SDK 下发后进行补充。

记忆和地点数据来自当前 HTTPS 后台；地图图片由后台使用高德 Web 服务 Key 获取。手机麦克风录音保存在 App 私有目录，不上传后台；演示转写需要核对。设备通信、录音豆音频、实时语音识别和模型推理仍需官方 SDK。当前后端是比赛演示单租户服务。

## 构建与运行

使用 Android Studio 打开本目录，JDK 21、Android SDK 36。若 Gradle 依赖尚未缓存，可用本地 SDK 工具：

```powershell
$env:XUNYI_DEMO_TOKEN_FILE = 'C:\Users\small\.codex\xunyi-private\app-token.txt'
.\build-demo.ps1
$env:ANDROID_AVD_HOME = 'E:\Android\Avd'
E:\Android\Sdk\emulator\emulator.exe -avd Pixel_7_API_36 -port 5558
```

确认 `adb -s emulator-5558 emu avd name` 返回 `Pixel_7_API_36` 后安装 `build-manual/xunyi-demo.apk`。5555 端口的雷电模拟器不能作为安装目标。

脚本从仓库外读取独立演示令牌，生成文件位于忽略的 `build-manual/gen`。服务地址固定在 `BackendConfig`，用户界面没有后台配置入口。不要把令牌、签名文件或构建 APK 推入 Git；APK 中令牌能被提取，赛后应轮换。

## 0.0.1 alpha 发布包

应用图标采用“声纹年轮”造型，使用品牌绿与暖杏色；共忆与“我的”页使用同一套老人、录音矢量图标。发布版本号为 `0.0.1-alpha`（versionCode 1）。

```powershell
.\build-release.ps1
```

此脚本从仓库外的 `C:\Users\small\.codex\xunyi-private\release-signing.json` 读取发布签名，输出 `build-manual/xunyi-v0.0.1-alpha.apk` 和对应 SHA-256 文件。签名配置、私钥和后台令牌不得提交到仓库。以后升级版本必须保留同一发布私钥；普通调试包与发布包使用不同签名，不能直接覆盖安装。

当前 GitHub 仓库为私有仓库，alpha 发布包提供给比赛团队试用。包内演示访问令牌可被提取，正式面向公众分发前需要接入用户身份认证并撤销该令牌。
