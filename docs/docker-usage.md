# Docker 啟動指引

## 建構映像
在專案根目錄執行：
```bash
docker build -t ocgp .
```

## 執行容器
```bash
docker run --rm -p 8080:8080 -p 8091:8091 \
  -e OCGP_PORT=8080 \
  -e OCGP_WS_PORT=8091 \
  -e OCGP_DB_PATH=/data/ocgp.sqlite \
  -v $(pwd)/data:/data \
  ocgp
```
- `8080`：HTTP / API
- `8091`：WebSocket
- `/data`：掛載資料庫檔案，避免容器刪除後資料遺失

## 前端存取
- 瀏覽器開啟 `http://localhost:8080`

## 環境變數
- `OCGP_PORT`：HTTP 埠（預設 8080）
- `OCGP_WS_PORT`：WebSocket 埠（預設 8091）
- `OCGP_DB_PATH`：資料庫檔案路徑（預設 `out/data/ocgp.sqlite`）

## 清理
```bash
docker rmi ocgp
```
