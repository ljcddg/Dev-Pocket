# Dev Pocket · 口袋助手

一个 Android 小工具，把日常开发要用到的**账户余额**和**每日签到积分**收进一个 App：不用开电脑、不用翻网页控制台，抬手就能看还剩多少额度、今天签到了没有。

> 个人自用项目。所有数据直连官方接口，本地加密存储，没有中转服务器，也不会上传任何密钥。

---

## 功能

| 页面 | 能力 |
| --- | --- |
| **余额** | 管理多个 AI 平台密钥（DeepSeek / Kimi / 智谱 GLM），逐个查询账户余额，支持一键刷新全部与定时刷新 |
| **签到** | 登录 WorkBuddy 账号，查看连续签到天数、剩余额度、签到累计积分；单独签到，或点右上角一键签到全部未签账号 |
| **积分详情** | 短按账号卡片进入：剩余额度、总计 / 已用、连签奖励进度、28 天签到日历、套餐明细 |
| **设置** | 自动签到（时间 / 随机浮动 / 仅工作日 / 开机恢复）、余额刷新间隔、三档外观、密钥数量与版本信息 |

细节上做了这些：

- **密钥加密**：API Key 经 Android Keystore 硬件加密后落盘（AES-256/GCM，每条记录独立随机 IV），不落明文、不参与备份（`allowBackup=false`）
- **三家商家**：添加密钥时选商家，App 按商家调用对应的余额接口。不做「自动识别」——DeepSeek 和 Kimi 的密钥都是 `sk-` 开头，靠前缀猜必然出错
- **多密钥管理**：一个账号一张卡片，可改名、可删除，同一密钥自动去重，卡片上标出所属商家
- **定时刷新**：可设 1 / 5 / 30 / 60 分钟或自定义间隔，页面停留期间按间隔轮询；离开页面立即停止，App 在后台不产生任何请求
- **一键签到**：右上角一次点完所有账号。账号之间串行、间隔 1~3 秒，今日已签的账号在本地就跳过、不发请求，避免并发突发这种一眼能看出是脚本的节奏
- **自动签到**：设定时间后到点自动执行。随机浮动**在调度时就算好**，不是执行时 `sleep`——Android 后台进程活不过一分钟，睡着的闹钟一定会被 Doze 掐掉；偏移用三角分布（人的作息有峰值，均匀随机反而是机器特征），周末再整体后移 25~45 分钟，秒级抖动避免每天同一秒
- **闹钟自愈**：开机、覆盖安装、改系统时间、换时区都会重排；主闹钟后 90 分钟还挂一个补偿闹钟，失败能再试一次
- **签到日历**：最近 28 天已签到日期以蓝色格子标出，一眼看出有没有断签
- **令牌自动续期**：accessToken 到期前静默用 refreshToken 换取新令牌，长期不用重新登录
- **原始数据诊断**：长按账号卡片可查看接口原始返回，接口字段变了能立刻定位
- **零第三方网络库**：仅用 `HttpURLConnection` + `org.json`，APK 体积小、依赖少

---

## 安装

前往 [Releases](https://github.com/ljcddg/Dev-Pocket/releases) 下载最新的 `DevPocket-v1.5.apk` 直接安装（需允许安装未知来源应用）。

- 最低支持 Android 7.0（API 24）
- 已在 Android 模拟器上验证冷启动、签到页、积分详情页与密钥输入；尚未做真机实测
- 覆盖升级不需要卸载——同名同签名即可

---

## 使用

### 一、查余额

支持三家，添加时先选商家再填密钥：

| 商家 | 密钥格式 | 余额接口 |
| --- | --- | --- |
| DeepSeek | `sk-...` | `GET api.deepseek.com/user/balance` |
| Kimi（月之暗面） | `sk-...` | `GET api.moonshot.cn/v1/users/me/balance` |
| 智谱 GLM | `xxxxx.xxxxx` | `GET open.bigmodel.cn/api/paas/v4/users/me/balance` |

1. 到对应平台的控制台创建 API Key
2. App 内点右上角 **＋**，选商家，粘贴密钥，可顺手起个备注名（如「主力号」）
3. 保存时 App 会先按所选商家真实验一次余额，验通了才入库，密钥填错或商家选错当场就知道
4. 点卡片可展开明细，点「刷新」只查这一条，右上角刷新图标查全部
5. 想让它自动轮询，到「设置 → 余额 → 自动刷新间隔」选 1 / 5 / 30 / 60 分钟或自定义

> Kimi 有个坑：国内站 `api.moonshot.cn` 和国际站 `api.moonshot.ai` 的密钥互不通用，用错站点会直接 401。本 App 走国内站。

### 二、WorkBuddy 每日签到

1. 签到页点右上角 **＋** 进入登录页
2. 在页面里完成登录，App 每 2 秒轮询一次结果，登录成功自动返回
3. 登录后点「签到」领今日积分，点卡片进详情看进度与日历；右上角双勾图标可对全部账号一键签到

**自动签到**（设置页）：

1. 打开「开启自动签到」，默认 08:00，可点时间行改成任意时刻
2. 「随机浮动」建议保留 0–30 分钟：实际执行时刻会在设定时间前后小幅偏移，每天不一样
3. 「开机自动恢复」控制重启后是否自动把闹钟挂回去，关掉后需要手动打开一次 App
4. 首次开启会引导授予「闹钟和提醒」权限（Android 12+ 必需），建议同时把应用加入电池优化白名单
5. 「立即试运行一次」可以马上验证链路，结果会显示在通知栏和「上次执行」一行

**登录渠道的实际情况**（这点比较重要，能省掉不少试错）：

| 渠道 | App 内可用 | 说明 |
| --- | --- | --- |
| 手机号验证码 | ✅ | 推荐，全流程在 App 内闭环 |
| 邮箱验证码 | ✅ | 同上 |
| 微信登录 | ❌ | 跳的是微信小程序，登录态留在微信里，回传不到 App |
| 微信扫码 | ❌ | 手机没法扫自己屏幕 |
| QQ / 企业微信 | ⚠️ | 取决于账号类型，个人号一般用不上 |

点「微信登录」时 App 会拦下并提示，引导改用验证码；若坚持用微信，可以点弹窗里的「在浏览器打开」，但登录结果同样不会自动回传，回来需手动点「我已登录」。

### 三、积分与额度的区别

这两个数字容易混，App 里刻意分开显示：

| 字段 | 含义 | 变化方向 |
| --- | --- | --- |
| **剩余额度**（大号数字） | 本计费周期还能用多少 | 随消耗递减，周期刷新后重置 |
| **签到累计获得**（小字） | 签到活动一共赚了多少 | 只增不减 |

---

## 安全与隐私

- 所有请求直连官方域名（`api.deepseek.com` / `api.moonshot.cn` / `open.bigmodel.cn` / `copilot.tencent.com`），项目本身没有任何服务端
- DeepSeek API Key 使用 Android Keystore 生成的工作密钥加密，密文存在应用私有目录；设备被 root 前，其他应用读不到
- WorkBuddy 令牌（accessToken / refreshToken）同样加密存储，令牌内容仅用于调用其官方接口
- 未接入任何统计、埋点、崩溃上报 SDK
- 应用清单里有网络与自动签到相关权限：`INTERNET`、`ACCESS_NETWORK_STATE`、`RECEIVE_BOOT_COMPLETED`、`SCHEDULE_EXACT_ALARM`、`USE_EXACT_ALARM`、`POST_NOTIFICATIONS`、`FOREGROUND_SERVICE`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
- 其中 `USE_EXACT_ALARM` 在 Google Play 上有用途限制（仅限闹钟、日历类应用），本项目为侧载自用，不走商店分发

**注意**：加密只能防止本机其他应用读取，无法防御已经拿到 root 权限的攻击者。请勿在他人设备上保存密钥。

---

## 技术实现

| 模块 | 选型 |
| --- | --- |
| 语言 / 构建 | Kotlin，AGP 9.5.1，Gradle 9.6，JDK 25（Android Studio 自带 JBR） |
| 最低 / 目标版本 | minSdk 24，targetSdk 37 |
| UI | 原生 View + Material Components，ViewBinding，`BottomNavigationView` 三个 Tab |
| 网络 | `HttpURLConnection` + `org.json`，无线程池外的第三方库 |
| 加密 | Android Keystore（AES/GCM/NoPadding） |
| 主题 | `AppCompatDelegate` 三档切换，偏好持久化到 SharedPreferences |

### 调用的接口

> 均为客户端侧观察所得，**非公开文档接口**，服务端随时可能调整字段或路径。

| 用途 | 方法与路径 |
| --- | --- |
| DeepSeek 余额 | `GET https://api.deepseek.com/user/balance` |
| Kimi 余额 | `GET https://api.moonshot.cn/v1/users/me/balance` |
| 智谱 GLM 余额（主） | `GET https://open.bigmodel.cn/api/paas/v4/users/me/balance` |
| 智谱 GLM 余额（回退） | `GET https://open.bigmodel.cn/api/biz/account/query-customer-account-report` |
| 创建登录态 | `POST /v2/plugin/auth/state?platform=mobile` |
| 轮询登录结果 | `GET /v2/plugin/auth/token?state=xxx`（未完成返回 `code=11217`） |
| 刷新令牌 | `POST /v2/plugin/auth/token/refresh` |
| 签到状态 | `POST /v2/billing/meter/checkin-activity-status` |
| 领取积分 | `POST /v2/billing/meter/daily-checkin`（已签返回 `code=10001`） |
| 额度汇总 | `POST /billing/meter/get-user-resource-summary` |
| 免费 / 付费包明细 | `POST /billing/meter/get-user-resource-free-packages`、`.../paid-packages` |

WorkBuddy 的接口前缀有个坑：**签到族带 `/v2`，额度族不带**，写错会直接 404。

---

## 自行构建

```bash
# 1. 准备签名配置（仓库不含密钥，需自建）
cat > key.properties <<'EOF'
storeFile=keystore/your.jks
storePassword=你的密码
keyAlias=你的别名
keyPassword=你的密码
EOF

# 2. 首次需要生成自己的 keystore
keytool -genkeypair -v -keystore keystore/your.jks -alias your_alias \
        -keyalg RSA -keysize 2048 -validity 10000

# 3. 构建
export JAVA_HOME=/path/to/android-studio/jbr
./gradlew assembleRelease
```

产物在 `app/build/outputs/apk/release/app-release.apk`。

**关于签名**：`key.properties` 与 `keystore/` 都已加入 `.gitignore`，不会进入版本库——签名私钥泄露等于任何人都能发布冒充你的升级包。克隆本项目后必须换成自己的 keystore，否则只能构建 debug 包；用不同签名覆盖安装会因签名不一致被系统拒绝，需先卸载。

**关于 R8**：`release` 构建刻意关闭了代码优化（`optimization.enable = false`）。开启后 R8 会把 Kotlin 标准库的集合类降级为包内可见，导致继承了它的类在启动时抛 `IllegalAccessError`（崩溃点在 `androidx.startup.InitializationProvider`）。个人工具稳定性优先，这项成本（APK 约 2.6 MB → 4.8 MB）值得付。

---

## 已知限制

- WorkBuddy 侧的登录、签到、额度均为逆向观察所得，接口变更会导致功能失效，届时用「查看接口原始数据」排查
- 微信系登录在 App 内无法闭环，只能用验证码
- 签到日历只覆盖最近 28 天，取决于服务端返回的 `checkin_dates` 长度
- 自动签到依赖系统闹钟：在设置里「强制停止」过本应用后，所有闹钟会被系统清空，需要重新打开一次 App
- 国产 ROM（MIUI / HyperOS、EMUI、ColorOS 等）默认会拦截后台唤醒，需在「自启动管理」和「电池优化」里手动放行，否则闹钟可能不响。App 设置页顶部会提示当前状态
- 自动签到的浮动偏移基于本地随机种子，无法规避服务端的行为审计，也不改变「这是自动化请求」这一事实
- 不支持多语言，仅简体中文

---

## 版本历史

| 版本 | 主要变更 |
| --- | --- |
| **v1.7** | 新增 Kimi 与智谱 GLM 余额查询，添加密钥页可选商家；新增余额定时刷新（1/5/30/60 分钟或自定义，仅前台）；设置页按「外观 / 余额 / 自动签到 / 后台权限 / 关于」分组重排 |
| **v1.6** | 设置从余额页工具栏移到下方导航第三个 Tab；新增一键签到（串行执行、跳过已签、Snackbar 进度）；新增自动签到（定时闹钟、随机浮动、开机恢复开关、精确闹钟与电池优化引导）；签到逻辑抽成 `CheckinEngine` 供手动与定时共用 |
| **v1.5** | 显示真实剩余额度（不再把签到累计当余额）；新增套餐明细与「签到累计获得」；API Key 改明文输入；余额接口前缀修正并加回退 |
| v1.4 | 签到页显示累计积分；新增积分详情页（连签进度 / 28 天日历 / 明细）；修复 Release 因 R8 优化导致的启动闪退 |
| v1.2 | 新增三档外观切换；登录入口统一到右上角 ＋；重做登录页逻辑，修掉「点微信登录没反应」 |

更详细的逐版说明见 [README-打包指南.txt](README-打包指南.txt)。

---

## 免责声明

本项目为个人自用工具，与 DeepSeek、WorkBuddy 官方无任何关联，未获其授权或背书。使用前请自行确认符合相关服务的用户协议，因接口变更、账号风险或数据问题造成的损失由使用者自行承担。请勿将本项目用于商业分发。
