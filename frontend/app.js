const state = {
    token: null,
    user: null,
    games: {},
    rooms: [],
    activeRoom: null,
    poller: null,          // 房間內輪詢
    lobbyPoller: null,     // 大廳輪詢
    selectedCell: null,
    lastRoomStatus: null,
    lastCurrentPlayerId: null 
};

const dom = {
    authSection: document.getElementById("auth-section"),
    lobbySection: document.getElementById("lobby-section"),
    roomSection: document.getElementById("room-section"),
    userInfo: document.getElementById("user-info"),
    userName: document.getElementById("user-name"),
    loginForm: document.getElementById("login-form"),
    registerForm: document.getElementById("register-form"),
    createRoomForm: document.getElementById("create-room-form"),
    roomNameInput: document.getElementById("room-name"),
    roomGameTypeSelect: document.getElementById("room-game-type"),
    roomPrivateCheckbox: document.getElementById("room-private"),
    roomsList: document.getElementById("rooms-list"),
    backToLobbyBtn: document.getElementById("back-to-lobby"),
    roomTitle: document.getElementById("room-title"),
    roomStatus: document.getElementById("room-status"),
    roomActions: document.getElementById("room-actions"),
    playerList: document.getElementById("player-list"),
    boardContainer: document.getElementById("board-container"),
    moveHistory: document.getElementById("move-history"),
    logoutBtn: document.getElementById("logout-btn"),
    toast: document.getElementById("toast"),
    simpleModal: document.getElementById("simple-modal"),
    winnerText: document.getElementById("winner-text"),
    brandTitle: document.getElementById("brand-title")
};

// --- Board orientation helpers ---
const CHINESE_ROWS = 10;
const CHINESE_COLS = 9;

function isChinesePerspectiveFlipped(room) {
    if (!room || room.gameType !== "CHINESE_CHESS") return false;
    if (!state.user?.id) return false;

    // 依 playerIds / playerOrder：0 = 紅方(下方)，1 = 黑方(上方)；黑方需要翻轉讓己方在下
    const idx = (room.playerIds || []).indexOf(state.user.id);
    return idx === 1;
}

function mapChineseDisplayToActual(room, displayRow, displayCol) {
    if (!isChinesePerspectiveFlipped(room)) {
        return { row: displayRow, col: displayCol };
    }
    return {
        row: (CHINESE_ROWS - 1) - displayRow,
        col: (CHINESE_COLS - 1) - displayCol
    };
}

// --- Gobang hover preview helpers ---
function getGobangStoneClassForUser(room) {
    const order = room?.gameState?.playerOrder || [];
    const me = state.user?.id;
    const idx = order.indexOf(me);

    // 後端：playerOrder[0] => stone = 1 (黑)；playerOrder[1] => stone = -1 (白)
    if (idx === 1) return "white";
    return "black";
}

function addGobangHoverPreview(cell, room) {
    // 已有棋子就不加（包含正式或預覽）
    if (cell.querySelector(".stone")) return;

    const stone = document.createElement("div");
    stone.className = `stone ${getGobangStoneClassForUser(room)} preview`;
    cell.appendChild(stone);
}

function removeGobangHoverPreview(cell) {
    const preview = cell.querySelector(".stone.preview");
    if (preview) preview.remove();
}

function init() {
    loadStoredSession();
    bindEvents();
    if (state.token) {
        enterLobby();
    } else {
        showAuth();
    }
}

function loadStoredSession() {
    const token = localStorage.getItem("ocgpToken");
    const userJson = localStorage.getItem("ocgpUser");
    if (token && userJson) {
        state.token = token;
        state.user = JSON.parse(userJson);
        updateUserInfo();
    }
}

function updateUserInfo() {
    if (state.user) {
        dom.userName.textContent = `歡迎，${state.user.username}`;
        dom.userInfo.classList.remove("hidden");
    } else {
        dom.userInfo.classList.add("hidden");
        dom.userName.textContent = "";
    }
}

function bindEvents() {
    dom.loginForm.addEventListener("submit", async (event) => {
        event.preventDefault();
        const username = document.getElementById("login-username").value.trim();
        const password = document.getElementById("login-password").value.trim();
        if (!username || !password) {
            return;
        }
        try {
            const response = await apiRequest("/api/login", {
                method: "POST",
                body: JSON.stringify({ username, password })
            });
            establishSession(response);
            showToast("登入成功");
            enterLobby();
        } catch (error) {
            showToast(error.message || "登入失敗", true);
        }
    });

    dom.registerForm.addEventListener("submit", async (event) => {
        event.preventDefault();
        const username = document.getElementById("register-username").value.trim();
        const password = document.getElementById("register-password").value.trim();
        if (!username || !password) {
            return;
        }
        try {
            const response = await apiRequest("/api/register", {
                method: "POST",
                body: JSON.stringify({ username, password })
            });
            establishSession(response);
            showToast("註冊成功，已自動登入");
            enterLobby();
        } catch (error) {
            showToast(error.message || "註冊失敗", true);
        }
    });

    dom.createRoomForm.addEventListener("submit", async (event) => {
        event.preventDefault();
        const name = dom.roomNameInput.value.trim();
        const gameType = dom.roomGameTypeSelect.value;
        const isPrivate = dom.roomPrivateCheckbox.checked;
        if (!name || !gameType) {
            return;
        }
        try {
            const response = await apiRequest("/api/rooms", {
                method: "POST",
                body: JSON.stringify({ name, gameType, private: isPrivate })
            });
            showToast("已建立房間");
            dom.roomNameInput.value = "";
            dom.roomPrivateCheckbox.checked = false;
            await enterRoom(response.room.id);
        } catch (error) {
            showToast(error.message || "建立房間失敗", true);
        }
    });

    dom.backToLobbyBtn.addEventListener("click", () => {
        leaveRoom();
    });

    dom.logoutBtn.addEventListener("click", () => {
        logout();
    });

    // 點擊標題回大廳
    if (dom.brandTitle) {
        const goHome = () => {
            const modal = document.getElementById("simple-modal");
            if (modal && !modal.classList.contains("hidden")) {
                modal.classList.add("hidden");
            }

            if (state.token) {
                leaveRoom();
            } else {
                showAuth();
            }
        };

        dom.brandTitle.addEventListener("click", goHome);
        dom.brandTitle.addEventListener("keydown", (e) => {
            if (e.key === "Enter" || e.key === " ") {
                e.preventDefault();
                goHome();
            }
        });
    }
}

function establishSession(response) {
    state.token = response.token;
    state.user = response.user;
    localStorage.setItem("ocgpToken", state.token);
    localStorage.setItem("ocgpUser", JSON.stringify(state.user));
    updateUserInfo();
}

function logout() {
    // 登出前若在房內，嘗試通知後端離房
    requestLeaveActiveRoom();

    state.token = null;
    state.user = null;
    localStorage.removeItem("ocgpToken");
    localStorage.removeItem("ocgpUser");
    clearRoomPoller();
    clearLobbyPoller();
    updateUserInfo();
    showAuth();
}

function showAuth() {
    clearRoomPoller();
    clearLobbyPoller();
    dom.authSection.classList.remove("hidden");
    dom.lobbySection.classList.add("hidden");
    dom.roomSection.classList.add("hidden");
}

function enterLobby() {
    dom.authSection.classList.add("hidden");
    dom.roomSection.classList.add("hidden");
    dom.lobbySection.classList.remove("hidden");

    clearRoomPoller();
    startLobbyPoller(); // ✅ 大廳定時更新
    fetchGamesAndRooms();
}

function startLobbyPoller() {
    clearLobbyPoller();
    state.lobbyPoller = setInterval(async () => {
        await refreshRooms();
    }, 2500);
}

function clearLobbyPoller() {
    if (state.lobbyPoller) {
        clearInterval(state.lobbyPoller);
        state.lobbyPoller = null;
    }
}

async function fetchGamesAndRooms() {
    try {
        const gamesResponse = await apiRequest("/api/games");
        state.games = gamesResponse.games || {};
        populateGameSelect();
    } catch (error) {
        showToast(error.message || "載入遊戲列表失敗", true);
    }

    await refreshRooms();
}

function populateGameSelect() {
    dom.roomGameTypeSelect.innerHTML = "";
    Object.values(state.games).forEach((game) => {
        const option = document.createElement("option");
        option.value = game.code;
        option.textContent = `${game.name}`;
        dom.roomGameTypeSelect.appendChild(option);
    });
}

async function refreshRooms() {
    try {
        const response = await apiRequest("/api/rooms");
        state.rooms = response.rooms || [];
        renderRoomList();
    } catch (error) {
        showToast(error.message || "載入房間列表失敗", true);
    }
}

function renderRoomList() {
    dom.roomsList.innerHTML = "";
    if (!state.rooms.length) {
        dom.roomsList.innerHTML = "<p>目前沒有公開房間，快建立一個吧！</p>";
        return;
    }
    state.rooms.forEach((room) => {
        const card = document.createElement("div");
        card.className = "room-card";

        const info = document.createElement("div");
        const title = document.createElement("h3");
        title.textContent = room.name;
        info.appendChild(title);

        const meta = document.createElement("div");
        meta.className = "room-meta";
        const gameName = room.gameTypeName || room.gameType;
        const playerCount = (room.playerIds || []).length;
        const status = room.started ? "對戰中" : "等待開始";
        meta.textContent = `${gameName} ｜ ${playerCount}/2 ｜ ${status}`;
        info.appendChild(meta);

        const actions = document.createElement("div");
        const button = document.createElement("button");
        button.textContent = "加入";

        const alreadyInRoom = room.playerIds?.includes(state.user?.id);
        if (alreadyInRoom) {
            button.textContent = "進入";
            button.addEventListener("click", () => enterRoom(room.id));
        } else if (room.started) {
            button.disabled = true;
            button.textContent = "進行中";
        } else if (!state.user) {
            button.disabled = true;
            button.textContent = "請先登入";
        } else {
            button.addEventListener("click", () => joinRoom(room.id));
        }
        actions.appendChild(button);

        card.appendChild(info);
        card.appendChild(actions);
        dom.roomsList.appendChild(card);
    });
}

async function joinRoom(roomId) {
    try {
        await apiRequest(`/api/rooms/${roomId}/join`, { method: "POST" });
        showToast("已加入房間");
        await enterRoom(roomId);
    } catch (error) {
        showToast(error.message || "加入房間失敗", true);
        await refreshRooms();
    }
}

async function enterRoom(roomId) {
    try {
        clearRoomPoller();
        clearLobbyPoller(); // ✅ 進房後停止大廳輪詢
        state.lastRoomStatus = null; 
        
        const response = await apiRequest(`/api/rooms/${roomId}`);
        state.activeRoom = response.room;
        
        dom.lobbySection.classList.add("hidden");
        dom.roomSection.classList.remove("hidden");
        
        renderActiveRoom();
        
        state.poller = setInterval(async () => {
            try {
                const update = await apiRequest(`/api/rooms/${roomId}`);
                state.activeRoom = update.room;
                renderActiveRoom();
            } catch (error) {
                // ✅ 房間被刪除/不存在：自動回大廳
                showToast("房間已關閉或不存在，已返回大廳", true);
                leaveRoom();
            }
        }, 2500);
    } catch (error) {
        showToast(error.message || "無法進入房間", true);
        enterLobby();
    }
}

function requestLeaveActiveRoom() {
    if (!state.token || !state.user || !state.activeRoom) {
        return;
    }
    const roomId = state.activeRoom.id;
    // 不阻塞 UI，盡力通知後端即可
    void apiRequest(`/api/rooms/${roomId}/leave`, { method: "POST" }).catch(() => {});
}

function leaveRoom() {
    // ✅ 離開房間時通知後端：更新人數；若全員離開後端會刪房
    requestLeaveActiveRoom();

    state.activeRoom = null;
    state.selectedCell = null;
    state.lastRoomStatus = null;
    clearRoomPoller();
    dom.roomSection.classList.add("hidden");
    enterLobby();
}

function clearRoomPoller() {
    if (state.poller) {
        clearInterval(state.poller);
        state.poller = null;
    }
}

function renderActiveRoom() {
    const room = state.activeRoom;
    if (!room) {
        return;
    }

    const currentStatus = room.gameState?.status || room.status;

    // 結束彈窗只觸發一次
    if (currentStatus === "FINISHED" && state.lastRoomStatus !== "FINISHED") {
        triggerGameOverModal(room);
    }
    state.lastRoomStatus = currentStatus;

    // 輪到你提示：文字 + toast（只在「輪到你」那一刻觸發一次）
    const currentPlayerId = room.currentPlayerId ?? null;
    const isMyTurnNow = room.started && currentStatus === "IN_PROGRESS" && state.user?.id && currentPlayerId === state.user.id;
    const wasMyTurnBefore = state.lastCurrentPlayerId === state.user?.id;

    if (isMyTurnNow && !wasMyTurnBefore) {
        showToast("輪到你了");
    }
    state.lastCurrentPlayerId = currentPlayerId;

    dom.roomTitle.textContent = `${room.name} ｜ ${room.gameTypeName || room.gameType}`;

    // 房間狀態文字帶入「輪到你了」
    let statusText = "等待開始";
    if (room.started) {
        if (currentStatus === "FINISHED") {
            statusText = "對戰結束";
        } else {
            statusText = isMyTurnNow ? "對戰進行中（輪到你了）" : "對戰進行中（等待對手）";
        }
    }
    dom.roomStatus.textContent = statusText;

    renderRoomActions(room);
    renderPlayerList(room);
    renderBoard(room);
    renderMoveHistory(room);
}

function triggerGameOverModal(room) {
    const winnerId = room.gameState?.winnerId || room.winnerId;
    const isDraw = room.gameState?.draw || false;

    if (isDraw) {
        showSimpleResult("雙方平手", "DRAW", "平手");
        return;
    }
    if (!winnerId) return;

    const winnerPlayer = (room.players || []).find(p => p.id === winnerId);
    const winnerName = winnerPlayer ? winnerPlayer.username : "未知玩家";

    let winnerColorCode = "";
    const playerOrder = room.gameState?.playerOrder || [];
    if (playerOrder.length >= 2) {
        if (room.gameType === "CHINESE_CHESS") {
            if (winnerId === playerOrder[0]) winnerColorCode = "RED";
            else if (winnerId === playerOrder[1]) winnerColorCode = "BLACK";
        } else if (room.gameType === "GOBANG") {
            if (winnerId === playerOrder[0]) winnerColorCode = "BLACK";
            else if (winnerId === playerOrder[1]) winnerColorCode = "WHITE";
        }
    }

    // ✅ 新增：你贏了 / 你輸了
    let resultText = "";
    if (state.user?.id) {
        resultText = (winnerId === state.user.id) ? "你贏了" : "你輸了";
    }

    showSimpleResult(winnerName, winnerColorCode, resultText);
}

function renderRoomActions(room) {
    dom.roomActions.innerHTML = "";
    if (!state.user) {
        return;
    }

    const isHost = room.hostUserId === state.user.id;
    const playerCount = (room.playerIds || []).length;
    const currentStatus = room.gameState?.status || room.status;

    // 結束後：房主可重新開始
    if (isHost && room.started && currentStatus === "FINISHED") {
        const restartBtn = document.createElement("button");
        restartBtn.textContent = "重新開始";
        restartBtn.addEventListener("click", async () => {
            try {
                // 若彈窗還開著，先關掉避免遮擋
                window.closeModal?.();

                await apiRequest(`/api/rooms/${room.id}/restart`, { method: "POST" });
                showToast("已重置對局，請再次開始");

                // 清理 UI 狀態
                state.selectedCell = null;
                state.lastRoomStatus = null;
                state.lastCurrentPlayerId = null;

                const update = await apiRequest(`/api/rooms/${room.id}`);
                state.activeRoom = update.room;
                renderActiveRoom();
            } catch (error) {
                showToast(error.message || "重新開始失敗", true);
            }
        });
        dom.roomActions.appendChild(restartBtn);
        return;
    }

    // ✅ 未開始：房主可 start（原本邏輯保留）
    if (isHost && !room.started) {
        const startBtn = document.createElement("button");
        startBtn.textContent = playerCount >= 2 ? "開始對戰" : "等待其他玩家";
        startBtn.disabled = playerCount < 2;
        startBtn.addEventListener("click", async () => {
            try {
                await apiRequest(`/api/rooms/${room.id}/start`, { method: "POST" });
                showToast("已經開始對戰");
                const update = await apiRequest(`/api/rooms/${room.id}`);
                state.activeRoom = update.room;
                renderActiveRoom();
            } catch (error) {
                showToast(error.message || "無法開始對戰", true);
            }
        });
        dom.roomActions.appendChild(startBtn);
    }
}

function renderPlayerList(room) {
    dom.playerList.innerHTML = "";
    const players = room.players || [];
    const playerOrder = room.gameState?.playerOrder || [];
    const currentPlayerId = room.currentPlayerId;
    players.forEach((player, index) => {
        const chip = document.createElement("div");
        chip.className = "player-chip";
        let colorLabel = "";
        if (playerOrder.length >= 2) {
            if (player.id === playerOrder[0]) {
                colorLabel = getColorLabel(room.gameType, 0);
            } else if (player.id === playerOrder[1]) {
                colorLabel = getColorLabel(room.gameType, 1);
            }
        }
        chip.textContent = `${player.username}${colorLabel ? ` ｜ ${colorLabel}` : ""}`;
        if (player.id === currentPlayerId) {
            chip.style.background = "#254a8b";
            chip.style.color = "#fff";
        }
        dom.playerList.appendChild(chip);
    });
}

function getColorLabel(gameType, index) {
    if (gameType === "GOBANG") {
        return index === 0 ? "黑方" : "白方";
    }
    if (gameType === "CHINESE_CHESS") {
        return index === 0 ? "紅方" : "黑方";
    }
    return "";
}

function renderBoard(room) {
    dom.boardContainer.innerHTML = "";
    const game = room.gameState;
    if (!room.started || !game) {
        dom.boardContainer.innerHTML = "<p>等待對戰開始</p>";
        return;
    }
    if (room.gameType === "GOBANG") {
        renderGobangBoard(room);
    } else if (room.gameType === "CHINESE_CHESS") {
        renderChineseBoard(room);
    } else {
        dom.boardContainer.innerHTML = "<p>暫不支援的棋類</p>";
    }
}

function renderGobangBoard(room) {
    const game = room.gameState;
    const grid = document.createElement("div");
    grid.className = "board-grid gobang";

    const size = game.boardSize || 15;
    const board = game.board || [];

    for (let x = 0; x < size; x++) {
        for (let y = 0; y < size; y++) {
            const cell = document.createElement("div");
            cell.className = "cell";
            cell.dataset.x = x;
            cell.dataset.y = y;

            const value = board[x]?.[y] ?? 0;

            if (value !== 0) {
                const stone = document.createElement("div");
                stone.className = `stone ${value === 1 ? "black" : "white"}`;
                cell.appendChild(stone);
            } else if (isPlayerTurn(room)) {
                // hover 半透明預覽
                cell.addEventListener("mouseenter", () => addGobangHoverPreview(cell, room));
                cell.addEventListener("mouseleave", () => removeGobangHoverPreview(cell));

                // click 落子
                cell.addEventListener("click", () => submitGobangMove(room, x, y));
            }

            grid.appendChild(cell);
        }
    }

    dom.boardContainer.appendChild(grid);
}

async function submitGobangMove(room, x, y) {
    try {
        await apiRequest(`/api/rooms/${room.id}/move`, {
            method: "POST",
            body: JSON.stringify({ x, y })
        });
        const update = await apiRequest(`/api/rooms/${room.id}`);
        state.activeRoom = update.room;
        renderActiveRoom();
    } catch (error) {
        showToast(error.message || "落子失敗", true);
    }
}

function renderChineseBoard(room) {
    const game = room.gameState;
    const board = game.board || [];

    const flipped = isChinesePerspectiveFlipped(room);

    const grid = document.createElement("div");
    grid.className = "board-grid chinese";

    // 用「畫面座標」迴圈，轉成「實際座標」取棋子
    for (let displayRow = 0; displayRow < CHINESE_ROWS; displayRow++) {
        for (let displayCol = 0; displayCol < CHINESE_COLS; displayCol++) {
            const cell = document.createElement("div");
            cell.className = "cell";
            cell.dataset.row = displayRow;
            cell.dataset.col = displayCol;

            const { row: actualRow, col: actualCol } = mapChineseDisplayToActual(room, displayRow, displayCol);
            const piece = board[actualRow]?.[actualCol] ?? null;

            if (piece) {
                const pieceEl = document.createElement("div");
                pieceEl.className = `piece ${piece.color.toLowerCase()}`;
                pieceEl.textContent = translatePiece(piece.type, piece.color);
                cell.appendChild(pieceEl);
            }

            if (state.selectedCell && state.selectedCell.row === displayRow && state.selectedCell.col === displayCol) {
                cell.classList.add("highlight");
            }

            if (isPlayerTurn(room)) {
                cell.addEventListener("click", () => handleChineseCellClick(room, displayRow, displayCol, piece));
            }

            grid.appendChild(cell);
        }
    }

    dom.boardContainer.appendChild(grid);
}


function translatePiece(type, color) {
    const map = {
        GENERAL: "將",
        ADVISOR: "士",
        ELEPHANT: color === "RED" ? "相" : "象",
        HORSE: "馬",
        CHARIOT: "車",
        CANNON: "炮",
        SOLDIER: color === "RED" ? "兵" : "卒"
    };
    return map[type] || "?";
}

function handleChineseCellClick(room, row, col, piece) {
    const playerIndex = room.playerIds.indexOf(state.user.id);
    if (playerIndex === -1) {
        return;
    }
    const playerColor = playerIndex === 0 ? "RED" : "BLACK";

    // 第一次點：只能選己方棋子
    if (!state.selectedCell) {
        if (piece && piece.color === playerColor) {
            state.selectedCell = { row, col }; // 存「畫面座標」
            renderBoard(room);
        }
        return;
    }

    // 若第二次點到己方棋子 => 變更選取
    if (piece && piece.color === playerColor) {
        state.selectedCell = { row, col };
        renderBoard(room);
        return;
    }

    // 送出 move：把「畫面座標」轉為「後端座標」
    const fromDisplay = state.selectedCell;
    const fromActual = mapChineseDisplayToActual(room, fromDisplay.row, fromDisplay.col);
    const toActual = mapChineseDisplayToActual(room, row, col);

    submitChineseMove(room, {
        fromRow: fromActual.row,
        fromCol: fromActual.col,
        toRow: toActual.row,
        toCol: toActual.col
    });

    state.selectedCell = null;
}


async function submitChineseMove(room, move) {
    try {
        await apiRequest(`/api/rooms/${room.id}/move`, {
            method: "POST",
            body: JSON.stringify(move)
        });
        const update = await apiRequest(`/api/rooms/${room.id}`);
        state.activeRoom = update.room;
        renderActiveRoom();
    } catch (error) {
        showToast(error.message || "移動失敗", true);
        renderBoard(state.activeRoom);
    }
}

function isPlayerTurn(room) {
    if (!state.user || !room.started) {
        return false;
    }
    if (!room.playerIds?.includes(state.user.id)) {
        return false;
    }
    return room.currentPlayerId === state.user.id;
}

function renderMoveHistory(room) {
    dom.moveHistory.innerHTML = "";
    const moves = room.gameState?.moves || [];
    if (!moves.length) {
        dom.moveHistory.innerHTML = "<p>尚未有任何步驟</p>";
        return;
    }
    moves.forEach((move) => {
        const li = document.createElement("li");
        if (room.gameType === "GOBANG") {
            li.textContent = `${move.moveNumber}. (${move.x}, ${move.y})`;
        } else if (room.gameType === "CHINESE_CHESS") {
            li.textContent = `${move.moveNumber}. (${move.fromRow},${move.fromCol}) → (${move.toRow},${move.toCol})`;
            if (move.captured) {
                li.textContent += ` 擊吃 ${translatePiece(move.captured, move.capturedColor)}`;
            }
            if (move.isCheck) {
                li.textContent += " (將軍！)";
                li.style.color = "#d0312d";
                li.style.fontWeight = "bold";
            }
        } else {
            li.textContent = `${move.moveNumber}. 進行動作`;
        }
        dom.moveHistory.appendChild(li);
    });
}

async function apiRequest(path, options = {}) {
    const headers = options.headers ? { ...options.headers } : {};
    if (options.body && !headers["Content-Type"]) {
        headers["Content-Type"] = "application/json";
    }
    if (state.token) {
        headers["X-Auth-Token"] = state.token;
    }
    const response = await fetch(path, {
        ...options,
        headers
    });
    const text = await response.text();
    let parsed = {};
    try {
        parsed = text ? JSON.parse(text) : {};
    } catch (error) {
        console.warn("無法解析 JSON", text);
    }
    if (!response.ok) {
        const message = parsed.error || `HTTP ${response.status}`;
        throw new Error(message);
    }
    return parsed;
}

function showToast(message, isError = false) {
    dom.toast.textContent = message;
    dom.toast.style.background = isError ? "rgba(192, 45, 45, 0.95)" : "rgba(30, 61, 115, 0.95)";
    dom.toast.classList.remove("hidden");
    setTimeout(() => {
        dom.toast.classList.add("hidden");
    }, 2400);
}

// --- 結算彈窗函式 ---
window.showSimpleResult = function (winnerName, colorCode, resultText = "") {
    const modal = document.getElementById("simple-modal");
    const winnerEl = document.getElementById("winner-text");
    const resultEl = document.getElementById("result-text");

    // reset class
    resultEl.classList.remove("win", "lose", "draw");

    if (colorCode === "DRAW") {
        resultEl.innerText = resultText || "平手";
        resultEl.classList.add("draw");
        winnerEl.innerText = "雙方平手！";
        modal.classList.remove("hidden");
        return;
    }

    // 結果字樣
    if (resultText === "你贏了") resultEl.classList.add("win");
    else if (resultText === "你輸了") resultEl.classList.add("lose");
    resultEl.innerText = resultText;

    let colorName = "";
    if (colorCode === "RED") colorName = "紅方";
    else if (colorCode === "BLACK") colorName = "黑方";
    else if (colorCode === "WHITE") colorName = "白方";

    winnerEl.innerText = `${winnerName} (${colorName}) 獲勝！`;
    modal.classList.remove("hidden");
};

window.closeModal = function() {
    document.getElementById('simple-modal').classList.add('hidden');
}

window.returnToLobby = function() {
    leaveRoom();
    window.closeModal();
}

init();