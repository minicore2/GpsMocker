# GPS Mocker 📍

Android GPS 模擬工具，專為開發者測試位置功能設計。

## 功能

| 模式 | 說明 |
|------|------|
| **模式一：固定點** | 點選地圖任意位置，App 持續發送該座標為假 GPS |
| **模式二：路線移動** | 設定起點 + 終點，以 **1.5 m/s(可設定)** 速度線性移動座標 |

---

## 環境需求

- Android Studio Hedgehog 以上
- Android 10+ (API 29+)
- 手機需開啟**開發者模式**

已測試:
1. Samsung A22 (Android 13 Samsung原廠)
2. Samsung Galaxy S10+ (Android 13 Pixel Experience ROM) 
3. Mi 11 Ultra (Android 14 Mi HyperOS 2.0)


---

## 使用前設定（重要！）

### 步驟 1：安裝 App 到手機

```bash
# 在 Android Studio 直接 Run，或：
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 步驟 2：設定模擬位置 App

1. 手機進入 **設定 → 開發者選項**
2. 找到「**選取模擬位置應用程式**」
3. 選擇 **GPS Mocker**

> ⚠️ 沒有這個步驟，Mock Location 會被系統拒絕！

### 步驟 3：使用

**模式一（固定點）：**
1. 選擇上方「📍 固定點」
2. 在地圖點選目標位置（會出現標記）
3. 按「▶ 開始」→ 系統 GPS 開始回報該位置
4. 按「⏹ 停止」結束

**模式二（路線移動）：**
1. 選擇上方「🚶 路線移動」
2. 點選地圖設定**起點**（綠色標記）
3. 再次點選地圖設定**終點**（紅色標記）
4. 按「▶ 開始」→ 座標以 1.5 m/s 從起點向終點移動
5. 抵達終點後會停在終點繼續發送

完整動作方式：
1. 安裝PikminGPSMocker
2. 開啟手機開發者模式，請參照網路各家手機開啟方式。
3. 進入手機設定，開發人員選項，拉到最下方點擊選取模擬位置應用程式，點擊GPS Mocker。
4. 進入Google Play安裝Google Health Connect(Android 13以前，Android 14後已內建)還有Google Fit。
5. 進入Health Connect，拉到權限和資料，點擊應用程式權限。
6. 點擊GPSMocker選擇全部允許（步數）。確認Google Fit也能允許讀取。
7. 開啟Google Fit選擇右下方個人資料。點擊上方照片旁的齒輪進入設定。到健康資料同步選項，打開讓Fit與健康資料同步。下方追蹤偏好設定項將追蹤你的活動給關閉。
8. 在遊戲當中進入設定－＞隱私權與步數－＞步數，選擇使用之前的追蹤方式(Google Fit)。


---

## 專案結構

```
GpsMocker/
├── app/src/main/
│   ├── java/com/devtool/gpsmocker/
│   │   ├── ui/
│   │   │   └── MainActivity.kt       # UI、地圖互動
│   │   └── service/
│   │       └── MockLocationService.kt # GPS 注入核心服務
│   ├── res/
│   │   ├── layout/activity_main.xml
│   │   ├── drawable/                 # 標記圖示、狀態點
│   │   └── values/                  # 顏色、字串、主題
│   └── AndroidManifest.xml
├── app/build.gradle
└── build.gradle
```

---

## 技術細節

- **OSMDroid 6.1.18** — OpenStreetMap 地圖（無需 API Key）
- **LocationManager.addTestProvider** — Android 原生 Mock GPS API
- **ForegroundService** — 背景持續注入，避免被系統殺掉
- **Coroutines** — 非同步座標更新，每 500ms 一次（= 0.75m/tick → 1.5 m/s）
- **Haversine 公式** — 計算兩點之間的實際距離與分割插值

---

## 常見問題

**Q: 按開始後其他 App 的 GPS 沒變？**
→ 確認有在開發者選項設定 GPS Mocker 為模擬位置 App

**Q: 地圖載不出來？**
→ 確認手機有網路連線（OSMDroid 需要下載地圖磚）

**Q: App crash on start?**
→ 確認已授予「位置」權限


