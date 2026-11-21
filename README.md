# 简·猫抓虫

给猫玩的安卓平板小游戏：白底上虫子从屏幕一边爬到另一边，猫爪按到就"啪"地压扁，一只一分。

- 纯 Canvas 绘制，无第三方依赖，APK 约 1 MB，老平板也跑得动
- 支持多指同时按（猫是两只爪子一起拍的），判定半径放大到约 22dp + 虫身 0.85 倍
- 5 种虫子：甲虫、蟑螂、蜘蛛、飞蛾、蜈蚣，速度/步态各不相同（直行、之字、走走停停）
- 偶尔出现"胖虫"，一只 3 分
- 压扁有压扁动画、碎屑飞溅、涟漪和"咔嚓"音效
- 沉浸式全屏 + 常亮，屏蔽导航栏误触；返回键不会直接退出
- 三指长按 1.5 秒呼出设置（猫爪碰不出这个手势）
- 内置 GitHub Release 检查更新

## 操作

| 动作 | 效果 |
| --- | --- |
| 单指/多指按到虫子附近 | 抓住，+1 分（胖虫 +3） |
| 三指按住 1.5 秒 | 打开设置面板 |
| 返回键 | 同设置面板 |

## 设置项

虫子速度（慢/中/快）、同屏虫子 1~3 只、音效、角落分数、暖白底色、震动反馈、重新开局、检查更新。

## 构建

需要 JDK 17 + Android SDK 34：

```bash
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

签名用的是 `keystore/catbug.jks`（别名 `catbug`，密码 `catbug2026`）。这是个人玩具项目，把证书放进仓库是为了让本地构建和 GitHub Action 产出的 APK 签名一致，能从旧版本直接覆盖安装。如果要正式发布，请换成自己的私密证书并通过 Secrets 注入。

## 发版 / 更新流程

**方式一（推荐，无需本地环境）**：GitHub 仓库 → Actions → `Release APK` → Run workflow。会自动构建并创建 `v版本号` 的 Release，APK 挂在 Release 里。

**方式二（本地打标签）**：

1. 改 `app/build.gradle` 里的 `versionCode`（必须递增）和 `versionName`
2. 提交并打标签：`git tag v1.0.1 && git push origin v1.0.1`
3. Action 会构建 APK 并挂到 Release 上

App 内「检查更新」读取 `releases/latest`，比较版本号后跳转到下载地址。

## 目录

```
app/src/main/java/com/jerryzwj/catbug/
├── MainActivity.kt     全屏、常亮、沉浸式、面板开关
├── GameView.kt         游戏循环、虫子行为与绘制、触摸判定
├── Sfx.kt              实时合成"咔嚓"音效（SoundPool）
├── Prefs.kt            本地设置
├── SettingsPanel.kt    三指呼出的设置面板
└── Updater.kt          GitHub Release 版本检查
```
