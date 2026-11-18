# 与上游 Aerial 的关系

果蝇从 `pengchujin/aerial-tv` 的 **Aerial 1.95** 分出，基线提交是 `a0f4b59`（HANDOFF）。
包名沿用 `app.orchard.tv`（改包名会让用户当成另一个 App，且要重新授权桌面权限）。

**本分支已经移除上游更新检查**：控制中心不再有更新入口，`Updates.kt` 的探测是空操作。
这是刻意的——否则上游会把品牌、图标和界面改回去。代价是没有自动更新通道，
也不会自动吃到上游修复。

## 远端设置

当前本地仓库的 `origin` 还指着上游。准备独立发布时改成分工明确的两个远端：

```bash
git remote rename origin upstream
git remote add origin https://github.com/<你的账号>/<你的新仓库>.git
git push -u origin main
```

以后 `origin` = 自己的果蝇仓库，`upstream` = Aerial 上游。

## 挑上游修复的流程

```bash
git fetch upstream
git log --oneline HEAD..upstream/main          # 上游有、我们没有的提交
git show <commit>                               # 先读懂改了什么
git cherry-pick -x <commit>                     # 只挑需要的，-x 留下来源
./gradlew :app:assembleStandardRelease --no-configuration-cache --console=plain
```

挑完按 [TESTING.md](TESTING.md) 的冒烟清单过一遍，再决定发不发版。

## 挑什么、不挑什么

**可以挑**：崩溃、兼容性、解码/渲染正确性、安全修复；Android 新版本的 API 适配；
纯后端逻辑且没有品牌文案的 bug 修复。

**不要挑**：Aerial 的品牌/名称/图标/官网链接；更新检查与版本探测；控制中心里的更新页；
上游首页布局与默认图标风格；会覆盖本分支个性化分区顺序、屏保类型、拼图改动的提交。

冲突是常态。宁可只挑一个小提交手工照着改，也不要整段 merge 把本分支的取舍冲掉。

## 版本编号

- `versionName`：果蝇自己的编号，从 1.22 起算（1.95 是上游的，不沿用）。当前 1.24。
- `versionCode`：每次正式发版 +1。当前 136，是第一款用果蝇正式签名密钥构建的版本。
- 换签名密钥会让所有老用户无法覆盖安装，只能卸载重装；密钥一旦发布就要永久保管。

## 可选：定期检查

如果想知道上游有没有新东西，可以设一个每周任务执行 `git fetch upstream &&
git log --oneline HEAD..upstream/main`，把结果发给自己。只检查、不自动合并。
