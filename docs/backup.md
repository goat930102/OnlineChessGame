# SQLite 備份與還原指引

## 備份
1. 確認服務關閉，或使用 WAL 模式下的安全備份命令。預設資料庫路徑：`out/data/ocgp.sqlite`（可用環境變數 `OCGP_DB_PATH` 覆寫）。
2. 直接複製檔案（關閉服務時）：  
   ```bash
   cp out/data/ocgp.sqlite out/data/ocgp-$(date +%Y%m%d%H%M%S).sqlite
   ```
3. 若服務仍在運行，使用 `sqlite3` 的 `.backup`：  
   ```bash
   sqlite3 out/data/ocgp.sqlite ".backup 'out/data/ocgp-backup.sqlite'"
   ```

## 還原
1. 停止服務。  
2. 覆寫主資料庫檔：  
   ```bash
   cp out/data/ocgp-backup.sqlite out/data/ocgp.sqlite
   ```
3. 重新啟動服務（`run.ps1` / `run.cmd` / Docker）。  

## 建議
- 將備份檔放在受保護的目錄並搭配排程任務（如 Windows Task Scheduler 或 cron）。  
- 備份前後檢查檔案權限，避免未授權讀取。  
