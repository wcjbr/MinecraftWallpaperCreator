# MinecraftWallpaperCreater

将 Minecraft 游戏画面导出为可循环壁纸序列，并生成可直接用 `mpv` 播放的清单与启动脚本。

## 当前实现

- GUI 配置页
- 中英文 i18n 文本
- JSON 配置持久化
- 生成 `playlist.m3u`
- 生成 `wallpaper-loop.sh`
- 自动生成 loop 过渡拼接帧
- 过渡帧采用 Rust 侧的像素级插值与运动权重衰减，减少双影和瞬移
- 导出收尾可 fork Rust worker，通过 IPC 在游戏外完成后处理
- 自动选择更适合循环的起点帧，避免强行尾帧接回首帧
- 采集时可自动隐藏 HUD
- 运行时检测 `Sodium` / `Iris`
- 服务端安全：公共入口只做日志初始化，不加载客户端逻辑

## 用法

进入世界后按：

- 按 `F8`
- 按 `F9` 打开配置界面
- 或在 `Mod Menu` 中打开本模组的 `Config`

配置界面支持：

- `Frame count`：总采集帧数
- 固定按渲染帧采集，并限制为 `24 FPS`
- `Blend frames`：loop 首尾过渡帧数量
- `Hide HUD while capturing`：采集时自动隐藏 HUD
- `Auto blend loop`：自动生成首尾混合拼接帧

配置保存到：

```text
<gameDir>/config/minecraftwallpapercreater.json
```

## Mod Menu 集成

项目已接入 `Mod Menu` + `Cloth Config` 配置入口。

当前开发环境默认从你的本地 HMCL 实例读取：

```text
/home/archzero/.config/hmcl/.minecraft/versions/1.21.11-Fabric/mods/modmenu-17.0.0.jar
/home/archzero/.config/hmcl/.minecraft/versions/1.21.11-Fabric/mods/cloth-config-21.11.153-fabric.jar
/home/archzero/.config/hmcl/.minecraft/versions/1.21.11-Fabric/.fabric/processedMods/cloth-basic-math-*.jar
```

如果你之后换实例或换版本，只要同步修改 `gradle.properties` 里的：

- `hmcl_instance_dir`
- `modmenu_jar_name`
- `cloth_config_jar_name`

## 输出目录

导出内容位于：

```text
<gameDir>/wallpaper-exports/<timestamp>/
```

目录中包含：

- `frames/frame-00000.png` 等帧图片
- `loop-transitions/transition-00000.png` 等运动补偿过渡帧
- `playlist.m3u`
- `wallpaper-loop.sh`
- `loop-info.properties`
- `capture.properties`

其中循环段不会再固定从第 1 帧开始，而是会根据多帧外观与运动一致性自动选择更自然的回环起点。

## mpv 播放

进入导出目录后执行：

```bash
bash wallpaper-loop.sh
```

## 已对齐的本地测试环境

本项目当前按以下 HMCL 实例版本做静态适配：

```text
/home/archzero/.config/hmcl/.minecraft/versions/1.21.11-Fabric
```

该实例中已确认存在：

- `iris-fabric-1.10.7+mc1.21.11.jar`
- `sodium-fabric-0.8.7+mc1.21.11.jar`
- `fabric-api-0.141.3+1.21.11.jar`
- `fabric-language-kotlin-1.13.10+kotlin.2.3.20.jar`

## 限制

当前版本是“稳定导出骨架”：

- 按渲染帧抓取当前 framebuffer 输出 PNG 序列，采样上限 24 FPS
- 生成 mpv 循环播放清单与 Rust 侧过渡帧，播放帧率可高于采集帧率
- 自动结合外观相似度、运动一致性和接缝跳变评分来选择循环切点
- 输出 loop 元数据，方便后续脚本读取
- 导出收尾由 Rust worker 处理，避免阻塞游戏主线程
- 可在采集时隐藏 HUD
- 可通过 GUI 调整配置
- 尚未内置视频编码
- 尚未实现自动相机轨迹/时间轴控制

后续如果继续做，可以补：

- 更高级的光流/运动补偿插帧
- 更强的无缝 loop 分析与自动选段
- 固定时长 loop 优化
- 自定义输出分辨率
