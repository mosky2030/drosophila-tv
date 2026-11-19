# 在 Mac 上接手果蝇（Android TV 桌面）的开发

这份文档写给**在 MacBook 上接着改这台电视桌面、以后从 Mac 推电视**的自己。
Windows 那台机器同时保留，两边共用一个 Git 远程仓库（`mosky2030/drosophila-tv`）。

Windows 那边已经把工程整理成两边都能编的状态：仓库里的路径不再写死任何一台机器的
JDK 或 SDK 位置，脚本行尾统一成 LF，可执行位也都在。真要动手，先照着下面这份清单走一遍。

---

## 0. 一分钟总览

| 要做的事 | 在哪做 | 说明 |
|---|---|---|
| 拉源码 | Mac，`git clone` | 仓库：`https://github.com/mosky2030/drosophila-tv.git` |
| 准备环境 | Mac 一次性 | JDK 17 + Android SDK + platform-tools |
| 写三份「机器本地」文件 | Mac 一次性 | `local.properties`、`~/.gradle/gradle.properties`、`local/signing.*` |
| 改代码 | Mac | 果蝇的源码在 `app/src/main/java/app/orchard/tv/` |
| 编译 | Mac | `./gradlew :app:assembleStandardRelease` |
| 推电视 | Mac | `TV=<ip> tools/install.sh`（小米走 ADB，TCL 走多屏互动） |
| 看效果 | Mac | Android TV 模拟器，见 [MAC_EMULATOR.md](MAC_EMULATOR.md) |
| 回 Windows | 改完 push | Windows 那边 `git pull` 接着改 |

---

## 1. 拉源码

```bash
cd ~/code
git clone https://github.com/mosky2030/drosophila-tv.git
cd drosophila-tv
git remote -v
```

应该看到两个 remote：

```
origin    https://github.com/mosky2030/drosophila-tv.git   ← 自己的，改完往这里推
upstream  https://github.com/pengchujin/aerial-tv.git      ← 上游 Aerial，只用来捞更新，不要往这里推
```

> 以后**只在 `origin` 上推**。`upstream` 保持只读。

---

## 2. 环境（Mac 那边已经在装了，这里只做核对）

### 2.1 JDK 17

Aerial/果蝇要 **JDK 17**，JDK 21+ 编译会挂。

```bash
brew install openjdk@17
/usr/libexec/java_home -V          # 看有哪些 JDK
```

Homebrew 装的话，路径是：

```
/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
```

（Intel Mac 是 `/usr/local/opt/openjdk@17/...`。）

### 2.2 Android SDK

```bash
brew install --cask android-commandlinetools
sdkmanager --sdk_root="$HOME/Library/Android/sdk" \
  "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

工程要 compileSdk 36 / build-tools 36。`platform-tools` 给出 `adb`。

### 2.3 屏幕录制/推送不需要额外装

推电视只用 `adb`，不依赖 ffmpeg。

---

## 3. 三份「每台机器各一份」的文件

这三份**都不进 Git**（仓库的 `.gitignore` 已经挡住了），所以 clone 下来之后必须自己补。
漏了哪一份的表现不一样，见第 8 节。

### 3.1 `local.properties` — 告诉 Gradle SDK 在哪

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
```

### 3.2 `~/.gradle/gradle.properties` — 告诉 Gradle 用哪个 JDK

**必须写全局的那份**（`~/.gradle/gradle.properties`），不要写进仓库里的 `gradle.properties`。
写进仓库的话，别人 clone 下来路径不对就编不了了 —— 这一条是仓库里 `gradle.properties`
特意留注释说明过的。

```bash
mkdir -p ~/.gradle
cat > ~/.gradle/gradle.properties <<'EOF'
org.gradle.java.home=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
EOF
```

> Apple Silicon 用上面那个路径；Intel Mac 换成 `/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`。

不想写这个文件也行：终端里 `export JAVA_HOME=<JDK17 路径>` 之后再跑 `./gradlew` 即可。
只有**从 Android Studio 里点构建**时才必须靠它（IDE 自带的是 JBR 25，不是 17）。

Windows 那台对应的是：

```
C:\Users\<你>\.gradle\gradle.properties
org.gradle.java.home=D:/WorkBuddyData/2026-11-12-18-45-54/android-env/jdk/jdk-17.0.21+9
```

### 3.3 签名材料 — `local/signing.properties` + `local/drosophila-release.jks`

这两个文件**在 Git 之外**，需要**另路拷贝**（U 盘 / 网盘 / 直接微信传都行），因为：

> **签名必须两台机器用同一把钥匙。** Android 靠签名判断「是不是同一个 App」。
> 钥匙不同，`adb install -r` 会被系统拒绝（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），
> 只能先卸载再装 —— 而卸载会把**果蝇的出生日期、养的时长、个性化设置**全清掉。

当前这把钥匙的信息（核对用）：

| 项 | 值 |
|---|---|
| 证书 | `CN=Drosophila, OU=Personal, O=Drosophila, L=Beijing, ST=Beijing, C=CN` |
| SHA-256 | `94:06:3C:0B:BD:47:1B:05:8D:46:24:33:47:58:28:5A:60:F5:F5:51:16:F9:5C:4F:9C:90:9E:78:CE:B2:8C:30` |
| 别名 | `drosophila` |
| 密钥库 | `local/drosophila-release.jks` |

放到 Mac 上后，长这样：

```
<repo>/local/drosophila-release.jks
<repo>/local/signing.properties
```

`signing.properties` 是四行：

```properties
storeFile=drosophila-release.jks
storePassword=<与 Windows 那份一致>
keyAlias=drosophila
keyPassword=<与 Windows 那份一致>
```

> `storeFile` 是**相对 `local/` 目录**写的，所以不要把 jks 改名、也不要放到别的目录。

**核对钥匙是不是同一把**（Mac 上，拿到 jks 之后）：

```bash
keytool -list -v -keystore local/drosophila-release.jks -alias drosophila | grep -i "SHA-256"
```

输出的 SHA-256 必须是上面那串。换了钥匙 → 电视上覆盖不了。

---

## 4. 编译

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :app:assembleStandardRelease
```

产物：

```
app/build/outputs/apk/standard/release/app-standard-release.apk
```

其它常用目标：

```bash
./gradlew :app:assembleStandardDebug      # 调试包（debug key，装不上电视，只在模拟器用）
./gradlew :app:assembleGuardRelease       # 守护版：固件不让装桌面时用
./gradlew test                            # 单元测试
```

第一次编译要下 Gradle 8.14.3（wrapper 指向腾讯云镜像，国内快）和一堆依赖，慢是正常的。

### 4.1 跑单元测试前先知道这件事

`./gradlew test` 里有 23 个 Robolectric 用例，它会**按 SDK 级别自己去 Maven 下 `android-all-instrumented` jar**。

Robolectric 默认连的是 `gcp.mirrors.maven.org` —— 这个域名在国内解析不了（DNS 超时），
而 `repo1.maven.org` 会 302 跳到同一个域名。所以直接跑测试会看到 23 个用例挂在
`UnknownHostException`，**不是代码坏了，是下不下来**。

工程里已经把源指到腾讯云镜像（和 Gradle 发行版用的是同一个）：

```kotlin
// app/build.gradle.kts 的 testOptions 里
val robolectricRepo = providers.gradleProperty("robolectricRepo")
    .getOrElse("https://mirrors.cloud.tencent.com/nexus/repository/maven-public")
```

要换源就加 `-P`：

```bash
./gradlew :app:testStandardDebugUnitTest -ProbolectricRepo=https://maven.aliyun.com/repository/public
```

**机器在代理后面的话**，Java 不读 shell 的 `http_proxy`，得写进全局 `~/.gradle/gradle.properties`：

```properties
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=<端口>
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=<端口>
```

> `systemProp.*` 自己**到不了 test JVM**（Gradle 只把它给守护进程）。
> 工程里已经把这两个值转发给测试进程了，所以上面那几行写一次就够。

---

## 5. 推电视

### 5.1 先把电视地址记下来

```bash
cp tools/tvs.env.example local/tvs.env
# 编辑 local/tvs.env，填上电视 IP
source local/tvs.env
```

`local/tvs.env` 也在 Git 之外，两台机器各填各的（IP 可能一样，但别假设）。

### 5.2 小米（ADB 直连）

```bash
BUILD=1 KEEP_ADB=1 TV=$XIAOMI_TV tools/install.sh
```

- `BUILD=1` = 用本地编的包，不去 GitHub 下载
- `KEEP_ADB=1` = 装完不停 ADB（电视待机会关 ADB，这个能保住）
- 首次连接电视上要弹「允许 USB 调试」，点允许

覆盖安装想快一点的话，装的时候顺手带上基线 profile（小米 MFTR0 需要）：

```bash
APK=app/build/outputs/apk/standard/release/app-standard-release.apk
unzip -p $APK assets/aerial-dm/31.dm > ${APK%.apk}.dm   # Android 9–11 用 28.dm
adb -s $XIAOMI_TV:5555 install-multiple -r $APK ${APK%.apk}.dm
```

### 5.3 TCL（固件拒绝 ADB 安装，走多屏互动）

```bash
BUILD=1 KEEP_ADB=1 TV=$TCL_TV tools/install.sh guard
```

`install.sh` 会自己起一个本地 HTTP 服务，把 APK 交给电视的「多屏互动」系统应用去装，
电视上会弹一个确认框 —— **要在电视上点「同意」**。

想手动推：

```bash
python3 tools/tcast-push.py <tv-ip> <apk-url> app.orchard.tv 果蝇 <versionCode>
```

### 5.4 撤销

```bash
TV=<ip> tools/restore.sh            # 收回所有授权和设置
TV=<ip> tools/restore.sh --uninstall # 连 App 一起卸
```

---

## 6. 在 Mac 上看效果（Android TV 模拟器）

模拟器不是必须的（推真机才是最终验证），但改 UI 时很方便。
**必须用 TV / Google TV 镜像**，手机镜像没有桌面模式。

完整步骤见 **[MAC_EMULATOR.md](MAC_EMULATOR.md)**，这里只列最关键的三条：

1. 用 `system-images;android-36;google-tv` 或 `android-tv` 镜像，手机镜像不行
2. 启动要加 `-gpu host`（软件渲染慢到不能用）
3. 装完要 `adb shell cmd package set-home-activity app.orchard.tv/...`

---

## 7. 果蝇的新增命令（ADB 改状态）

果蝇的年龄、只数是从「出生时间」算出来的（`PetGrowth`），所以要改它只要改出生时间。
release 包不是 debuggable，`run-as` 用不了，所以专门开了一条广播：

```bash
adb shell am broadcast -a app.orchard.tv.CONFIG \
  --es package app.orchard.tv \
  --ei pet_age_days 400
```

回读：

```bash
adb shell am broadcast -a app.orchard.tv.CONFIG \
  --es package app.orchard.tv \
  --es get pet_born_at
```

> 果蝇日记在「个性化」面板的桌宠那一行下面，写着「养了 N 天，长了 M 像素」。
> 出生时间改完**要重启 App** 才看得到（进程还持 着旧值）。

---

## 8. 常见坑

| 现象 | 原因 | 怎么办 |
|---|---|---|
| Gradle 起不来，报 JDK 版本 | 没指 JDK 17 | 写 `~/.gradle/gradle.properties`，见 3.2 |
| `SDK location not found` | 没有 `local.properties` | 见 3.1 |
| 测试挂在 `UnknownHostException: gcp.mirrors.maven.org` | Robolectric 下不到 android-all jar | 见 4.1（工程已指腾讯云镜像，还要确认代理配置） |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 签名和电视上那个不是同一把 | 换回正确的 jks；实在不行卸载重装（**会丢果蝇历史**） |
| 编译出来的是 debug 签名 | `local/signing.properties` 不在 | 见 3.3；`apksigner verify --print-certs` 核对 |
| `bash\r: No such file or directory` | 行尾变成 CRLF | 仓库的 `.gitattributes` 已处理；`git config core.autocrlf input` 再重新 clone |
| 模拟器启动很卡 | 用了软件渲染 | 加 `-gpu host` |
| 模拟器装完不是桌面 | 没设 HOME | `cmd package set-home-activity` |
| 航拍壁纸一直转圈 | 国内访问 Apple 的 CDN 超时 | 用内置壁纸；见 MAC_EMULATOR.md |
| `adb: device not found` | 电视待机关了 ADB | `KEEP_ADB=1` 重装；或 `adb connect <ip>:5555` |

---

## 9. 两台机器同时改怎么办

规矩很简单：

1. **各改各的分支**，别两边都往 `main` 直接推。
   - Mac：`git checkout -b mac/xxx`，改完 `git push origin mac/xxx`
   - Windows：改完也开自己的分支，推上去
2. 要合并时，进 GitHub 开 PR，或者本地 `git fetch origin && git rebase origin/main`。
3. **`local/` 目录两台机器各有一份**，内容不同是正常的（IP、SDK 路径都不一样）。
   `local/signing.*` 必须一致（同一把钥匙），其他随便。
4. **别在两台机器上同时跑同一个模拟器/同一台电视** —— ADB 会互相踢掉对方。
5. 合并时如果两边都改了 `app/src/main/res/drawable-nodpi/` 里的图（果蝇素材），
   二进制文件没法自动合，手动挑一边。

---

## 10. 仓库结构速查

```
app/src/main/java/app/orchard/tv/
  settings/PetGrowth.kt        果蝇成长算法（年龄 → 体型 / 只数）—— 改规则看这里
  settings/AerialSettings.kt   所有设置项的存取（含桌宠开关、出生时间）
  ui/home/PetOverlay.kt        果蝇本体：状态机 + 帧选择 + 渲染（含搓腿/理翅/落影）
  ui/wallpaper/WallpaperSheet.kt 个性化面板（果蝇日记那一行在这里）
  guard/ConfigReceiver.kt      ADB 配置命令（--es/--ez/--ei 都走这里）
tools/
  fly_bake.py                  烘翅膀帧
  fly_groom.py                 烘搓腿帧
  fly_shadow.py                烘落影
  install.sh / restore.sh      推电视 / 撤销
  tcast-push.py                TCL 多屏互动推送
docs/
  MAC_EMULATOR.md              Mac 上跑 TV 模拟器
  HANDOFF.md                   上游 Aerial 的完整交接史（很长，慎入）
  ARCHITECTURE.md              架构说明
  devices/                     各机型实测笔记
```

果蝇的规矩写在代码里每一处改动的「第 N 轮」注释块里 —— **改之前先把那一轮的注释读完**，
那里写着为什么这么写、改之前是什么样。直接改会踩历史坑。
