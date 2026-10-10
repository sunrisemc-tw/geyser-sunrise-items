# geyser-sunrise-items

日出伺服器的 Geyser extension：讓 **custom item mappings 從 GitHub release 動態載入**。

## 解決什麼問題

Geyser 的 custom item 註冊只在**開機**時跑一次（`Registries.populate()` → fire
`GeyserDefineCustomItemsEvent`）。`/geyser reload` 走的是 `startInstance()` 子集，
**不重跑 item 註冊**（原始碼實證：`GeyserImpl.start()` 在 `isReloading` 時只呼叫
`startInstance()`，略過含 `Registries.populate()` 的 `initialize()`）。

→ 所以 Geyser **原生沒有「不重啟換 mappings」的能力**。custom item 在開機時烤進每個
協定版本的物品調色盤，`reload`/`disable` 都不會重建。

## 這個 extension 的作用

把 mappings 的**真實來源**從「容器內本地檔」改成「GitHub release」：

- 開機 `GeyserDefineCustomItemsEvent` 當下，**直接從 GitHub release 的
  `geyser_item_mappings.json` 抓最新版**來註冊（經 gh-proxy CDN）。
- 下載失敗 → fallback 到上次成功的本地快取（`dataFolder/geyser_item_mappings.cached.json`），離線也能開機。
- 省掉每次手動把 mappings `docker cp` 進容器。CI 推新 release 後，伺服器只要**完整重啟 Geyser**就自動拉到最新。

## 仍需完整重啟才生效（架構限制，誠實說明）

這個 extension **無法突破** Geyser「custom item 開機凍結」的架構——它只是讓開機時抓的來源變動態。
改了物品要生效：**推新 release → 完整重啟 Geyser/proxy 容器**（不是 `/geyser reload`）。
好處是不用再手動部署 mappings 檔，且永遠對齊 GitHub 上的最新版。

## 建置

```bash
./gradlew build
# 產出 build/libs/geyser-sunrise-items-1.0.0.jar
```

需要 JDK 21。

## 安裝

把 jar 丟進 Geyser 的 `extensions/` 資料夾（`plugins/Geyser-Velocity/extensions/`），重啟。

## mappings 來源

`https://gh.gdtw.qzz.io/https://github.com/sunrisemc-tw/resourcepack/releases/latest/download/geyser_item_mappings.json`

由 [`sunrisemc-tw/resourcepack`](https://github.com/sunrisemc-tw/resourcepack) 的 release workflow
用 [`j2b-pack-converter`](https://github.com/sunrisemc-tw/j2b-pack-converter) 自動產生。

## 授權

MIT
