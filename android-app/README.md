# 寻忆 Android Demo

原生 Android Java 应用，按暖奶油色、清晰文字、大触控区域设计。底部为“首页／记忆／共忆／我的”。开发顺序和问题清单见[详细检查与设计](../DESIGN_REVIEW_2026-09-27.md)。

## 演示路径

1. 首页直接看到家庭记忆，设备卡显示当前连接状态。
2. 记忆页搜索 3 条服务端保存的示例记忆。第一条由 2 段独立讲述组成，可分别播放；示例讲述使用本地演示配音。首页也可进入录音收件箱，逐段查看讲述与关联记忆。
3. 详情中的高德地图显示记忆地点。点“核对录音中的地点”，可搜索地点，查看候选标点、名称和地址，再确认绑定。确认后关联记忆地点也会更新。
4. 记忆详情可直接修改服务端故事、调用系统分享面板分享文字，或删除整理结果而保留原始讲述。
5. 共忆页先展示示例对话。参与者同意后可录入长辈和家人的文字，服务器保存轮次并给出规则生成的开放追问。语音识别及大模型能力等待官方 SDK 下发后进行补充。

记忆和地点数据来自当前 HTTPS 后台；地图图片由后台使用高德 Web 服务 Key 获取。设备通信、真实硬件录音、实时语音识别和模型推理仍需官方 SDK。当前后端是比赛演示单租户服务。

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
