# 在 MacBook 的 Android TV 模拟器上跑果蝇

给 Mac 那台用的。目标是让「果蝇」这块桌面在笔记本屏幕上跑起来——安静、风扇不转、当屏保用。

Mac 那边的环境（`docs/HANDOFF.md` 记着 2026-10-28 那轮）本来就有：
JDK 17（`/opt/homebrew/opt/openjdk@17`）、Android SDK 在 `~/Library/Android/sdk`、
Gradle Wrapper 8.14.3、AGP 8.12、Kotlin 2.2.10。还跑过 Google TV 模拟器（Android 13）。
所以**这边不需要改代码就能跑**，要做的只是：装对镜像、装 APK、把它设成桌面。

---

## 1. Mac 上要准备的

### 1.1 必须是「电视」镜像，不能用手机镜像

果蝇是 **Leanback 启动器**（manifest 里 `LEANBACK_LAUNCHER` + `HOME` + `COMMON_HOME`）。
手机 AVD（Phone）里没有 Leanback 环境，它装得上、也起得来，但**不是桌面**：
按 HOME 键会回系统启动器，桌面身份也拿不到。

在 SDK Manager 里装系统镜像，勾其中一个：

| 镜像 | 包名 | 说明 |
|---|---|---|
| Android TV 13/14（Google APIs，**推荐**） | `system-images;android-34;android-tv;google_apis` 或 `android-35` | 桌面干净，没有 Google Play 商店的推荐流 |
| Android TV 11 | `system-images;android-30;android-tv;google_apis` | 老一点，兼容性最好 |

不要装 `google_apis_playstore` 的电视镜像——里面带 Play 商店的推荐墙，和果蝇的设计是拧着的。

### 1.2 AVD 建法

Device profile 选 **TV**，分辨率 1920×1080，landscape。内存给 4 GB 以上。

启动参数（Apple Silicon 上务必用 `-gpu host`）：

```bash
~/Library/Android/sdk/emulator/emulator -avd AerialTV -gpu host -no-snapshot
```

`-gpu swiftshader_indirect` 是软件渲染，慢到不能看——`docs/PERF-2026-10-28.md` 记过：
1080p 下每帧 GPU 40–100 ms 起，长按菜单每帧 200–300 ms。Apple Silicon 上 Metal 直通没问题。

### 1.3 把果蝇设成桌面

```bash
# 让它当默认桌面（模拟器上系统会问一次，选「始终」）
adb shell cmd package set-home-activity app.orchard.tv/app.orchard.tv.MainActivity
```

没设成桌面也能用：`adb shell am start -n app.orchard.tv/.MainActivity` 直接起，
只是按 HOME 键回不去它。

---

## 2. 这边给的东西

| 文件 | 路径 | 用途 |
|---|---|---|
| release APK（约 9.1 MB） | `app/build/outputs/apk/standard/release/app-standard-release.apk` | 日常用这个，体积小 |
| debug APK（约 26 MB） | `app/build/outputs/apk/standard/debug/app-standard-debug.apk` | 要看 logcat、要用 `run-as` 改设置时用 |

两个包都是 4 个 ABI 都带（arm64-v8a / armeabi-v7a / x86 / x86_64），Mac 上两种芯片都能装。

**装 standard 别装 guard。** guard 那一版是给国产电视做的：抢 HOME 键、装无障碍服务、
后台把自己复活。在模拟器上它只会跟系统自己的启动器打架，还会在后台常驻。

```bash
adb install -r app-standard-release.apk
```

---

## 3. 装完之后

### 3.1 开桌宠、并且**立刻**看到养了很久的果蝇

新装的是 18px 的新生儿，长到 180px 要半年。所以这边加了一条 ADB 命令，
一条广播直接把果蝇的出生时间往前挪，**不用改 settings.xml、也不需要 run-as**
（release 包本来就不是 debuggable，run-as 用不了）：

```bash
# 说这只果蝇是 400 天前孵的 → 一只满体型 + 一只 145px + 一只小的（正好「两大一小」）
adb shell am broadcast -a app.orchard.tv.CONFIG \
  -n app.orchard.tv/.guard.ConfigReceiver --ei pet_age_days 400
# 回：data="pet_born_at=1760531462147 age_days=400"

# 改回 newborn
adb shell am broadcast -a app.orchard.tv.CONFIG \
  -n app.orchard.tv/.guard.ConfigReceiver --ei pet_age_days 0
```

这条命令同时写 `settings.xml` 的 `pet_born_at` 和 `files/pet_birth` 两份，
所以重启进程、装新版本都不会把它冲掉——果蝇的历史在 Mac 上也是持久的。

果蝇开没开可以在个性化面板里切，也可以直接：

```bash
adb shell am broadcast -a app.orchard.tv.CONFIG \
  -n app.orchard.tv/.guard.ConfigReceiver --es screensaver mine
```

### 3.2 手机页：照片、视频、桌面设置

手机页在 8790 端口，转发到本机就能用 Mac 的浏览器开：

```bash
adb forward tcp:8790 tcp:8790
open http://127.0.0.1:8790
```

在手机页上可以：传照片当壁纸、传本地视频当屏保、改桌面排布、换磁贴图。

### 3.3 航拍壁纸取不到片怎么办

果蝇的航拍壁纸是**从 Apple 的 CDN 流式取**，不是本地文件。之前在小米和 TCL 上都遇到过
`ERROR_CODE_TIMEOUT`（走家里的路由超时）。国内网络大概率同样。

取不到的时候它会退回内置的 14 张渐变壁纸，功能不受影响，只是没有航拍。
想要航拍就用手机页传本地 MP4 当屏保。

### 3.4 看日志

```bash
adb logcat -s AerialPet Aerial
# AerialPet 那行每次启动都记一次果蝇的出生时间，出事时先看它：
# pet born: stored=... backup=... used=... mode=pixel
```

---

## 4. 已知会踩的坑

1. **手机镜像不行**，必须 TV 镜像（见 1.1）。
2. **`-gpu swiftshader_indirect` 会卡**，`-gpu host`（见 1.2）。
3. **系统启动器会来抢 HOME**：设成桌面时系统弹一次选择框，选「始终」；
   弹框没出现就再跑一次 `set-home-activity`。
4. **模拟器分辨率变了要重启应用**：果蝇按窗口实际尺寸算布局
   （`Wallpapers.fitScreen`），`adb shell wm size 1920x1080` 之后要 `force-stop` 再起。
5. **别装 guard 版**，理由见第 2 节。
6. **模拟器内存**：Mac 上内存不够时模拟器会自己重启（PERF-2026-10-28.md 记过一回），
   给 AVD 留够内存，关掉别的大内存程序。

---

## 5. 这边还能继续做的

- 果蝇日记（个性化面板里那行「养了 N 天，长了 M 像素，X 月 X 日会多一只」）在 Mac 上同样有，
  因为它读的是同一份出生时间。
- 搓腿 / 理翅动作帧、落影，都是 Kotlin 侧画的，模拟器和电视上是同一套代码，不用单独移植。
- 如果 Mac 上跑着顺，想把果蝇做成**网页版**（就是工作区里 `Aerial/web/` 那套 Aerial 屏保），
  那是另一件事：那边现在是一只 SVG 怪物，不是果蝇，果蝇得重写成 JS。那条路要单独立项。
