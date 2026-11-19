# 一键搬家（PhoneMigration）

一个 Android 原生工具类 App：把旧手机的**相册照片 + 视频 + 备忘录/记事本**打包迁移到新手机。

## 功能

- **三类文件一次打包**：照片、视频、备忘录 打进同一个压缩包，三条独立进度条并行推进，显示剩余秒数
- **自动分卷**：每卷大小自己填（0.05~10GB），默认 1.9GB，写满自动切下一卷，方便 QQ/微信直接发
- **免手动授权**：启动即自动申请权限，兼容 Android 5.1 ~ 15（minSdk 23 / targetSdk 29）
- **一键分享**：打包完弹出分享面板；「③ 分享数据包」可随时重发，可选单卷或全部
- **新手机直接看**：装同一个 App，点「② 选择全部分卷并查看」自动扫描公共目录并列出、预览文件
- **自愈引擎**：复制失败自动换方式重试 3 次、打包出错自动调大分卷重试 3 轮、异常落盘可查（🛠 自愈日志按钮）
- **多品牌备忘录**：小米 / 华为 / 三星 / ColorOS / vivo 等笔记目录自动扫描

## 截图

图标设计遵循 Swiss Minimalism 规范（Navy #1E3A5F + Green #059669），预览见 `icon_preview.png`。

## 构建

### 环境

- JDK 17 或 21
- Android SDK Platform 34 + Build-Tools 34.0.0
- Gradle 8.7（项目使用 AGP 8.5.2 / Kotlin 2.0.21）

### 出包

```bash
# Debug 版（免签名，直接可装）
gradle assembleDebug

# Release 版（需要先配置自己的签名，见下）
gradle assembleRelease
```

Windows 用户也可以双击 `一键构建.bat`。

### Release 签名配置（重要）

**签名密钥 `release.jks` 未提交到本仓库**（出于安全考虑，公开仓库绝不能放私钥）。
要构建正式版，请在 `app/` 下生成自己的 keystore：

```bash
keytool -genkeypair -v -keystore app/release.jks -alias phonemigration \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass 你的密码 -keypass 你的密码 \
  -dname "CN=YourName, O=YourOrg, C=CN"
```

然后在 `app/build.gradle` 的 `signingConfigs.release` 里改成你的 storeFile / 密码 / alias。
或者直接用 Debug 版：`gradle assembleDebug`，产物在 `app/build/outputs/apk/debug/`。

## 换图标

```bash
python gen_icon.py        # 按内置 Swiss Minimalism 风格生成图标
# 或：把图片改名为 图标/input.png，双击 换图标.bat
一键构建.bat              # 重新打包，图标生效
```

## 目录说明

| 文件 | 作用 |
|---|---|
| `app/src/main/java/.../MainActivity.kt` | 主逻辑：扫描 / 打包 / 分卷 / 分享 / 导入 / 自愈 |
| `app/src/main/java/.../SelfHealer.kt` | 自愈日志引擎 |
| `app/src/main/java/.../VideoCompressor.kt` | 视频压缩器（保留备用） |
| `app/src/main/AndroidManifest.xml` | 权限与组件声明 |
| `gen_icon.py` / `换图标.py` | 图标生成脚本 |
| `构建说明.md` | 完整版本迭代与真机验证记录 |
| `一键构建.bat` / `一键安装并授权.bat` / `换图标.bat` | Windows 一键脚本 |

## 许可证

仅供个人数据迁移使用。
