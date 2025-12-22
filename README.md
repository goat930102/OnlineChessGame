# 線上棋類遊戲平台 (OCGP) - 最小可執行專案

本專案依據《線上棋類遊玩平台需求文件》實作最小可執行版本。系統採前後端分離設計：

- **前端**：使用 HTML / CSS / JavaScript 建構，提供登入註冊、房間管理、棋盤互動等功能。
- **後端**：以純 Java 實作 HTTP 伺服器（使用 `com.sun.net.httpserver.HttpServer`），提供 RESTful API、房間管理、即時棋局狀態與遊戲規則驗證。

目前已支援的核心需求：

- 使用者註冊、登入與身分維護。
- 遊戲目錄（象棋、五子棋）查詢。
- 房間建立、列表查詢、加入與開始對戰。
- 兩人制遊戲流程控管（輪流行棋、勝負判定、棋譜記錄）。
- **五子棋**：15x15 棋盤、勝負判定（五子連線）、平手檢測。
- **象棋**：9x10 棋盤、完整棋子行棋規則與「將不見面」檢查、吃子判定。

## 專案結構

```
.
├── README.md
├── backend
│   ├── run.ps1               # 編譯並啟動伺服器的便利腳本
│   └── src/com/ocgp/server   # Java 原始碼
└── frontend
    ├── app.js
    ├── index.html
    └── styles.css
```

## 系統需求

- Java 17 以上（開發環境使用 Java 23）。
- Windows 環境可直接使用 `backend/run.ps1`；其他平台可參考下方手動指令。

## 快速啟動

1. 開啟 PowerShell，進入專案根目錄。
2. 執行：

   ```powershell
   cd backend
   .\run.ps1 -Port 8080
   ```

   成功後將在 `http://localhost:8080` 提供完整前後端服務。

### 手動編譯與啟動（跨平台）

```bash
# 於專案根目錄
javac --add-modules jdk.httpserver -d backend/out $(find backend/src -name "*.java")
OCGP_STATIC_DIR="$(pwd)/frontend" OCGP_PORT=8080 \
  java --add-modules jdk.httpserver -cp backend/out com.ocgp.server.Main
```

## API 一覽（摘錄）

- `POST /api/register`：註冊帳號。
- `POST /api/login`：登入並取得 `X-Auth-Token`。
- `GET /api/games`：遊戲列表。
- `GET /api/rooms`：房間列表，只列公開房間。
- `POST /api/rooms`：建立房間。
- `POST /api/rooms/{id}/join`：加入房間。
- `POST /api/rooms/{id}/start`：開始對戰（限房主）。
- `POST /api/rooms/{id}/move`：提交行棋或落子。
- `POST /api/rooms/{id}/leave`：離開房間；若房間變空房（0 人）則 排程 30 秒後刪除（期間有人 join 會取消）
- `POST /api/rooms/{id}/restart`：對戰結束後由房主重置對局（回到等待狀態）。

> 所有需要身分驗證的 API 皆須於請求標頭附上 `X-Auth-Token`。

## 測試建議

- 於瀏覽器開啟 `http://localhost:8080`，建立兩個不同帳號。
- 使用兩個瀏覽器視窗登入，加入同一房間並開始遊戲。
- 驗證輪流行棋、勝負判定與棋譜紀錄是否正確更新。

## 後續可擴充項目

- 加入資料庫儲存使用者與對局紀錄。
- 增加聊天室、觀戰模式、棋譜下載等功能。
- 擴充更多棋種與 AI 對戰。
