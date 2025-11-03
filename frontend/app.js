const state = {
    token: null,
    user: null,
    games: {},
    rooms: [],
    activeRoom: null,
    poller: null,
    selectedCell: null
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
    toast: document.getElementById("toast")
};

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
}

function establishSession(response) {
    state.token = response.token;
    state.user = response.user;
    localStorage.setItem("ocgpToken", state.token);
    localStorage.setItem("ocgpUser", JSON.stringify(state.user));
    updateUserInfo();
}

function logout() {
    state.token = null;
    state.user = null;
    localStorage.removeItem("ocgpToken");
    localStorage.removeItem("ocgpUser");
    clearRoomPoller();
    updateUserInfo();
    showAuth();
}

function showAuth() {
    dom.authSection.classList.remove("hidden");
    dom.lobbySection.classList.add("hidden");
    dom.roomSection.classList.add("hidden");
}

function enterLobby() {
    dom.authSection.classList.add("hidden");
    dom.roomSection.classList.add("hidden");
    dom.lobbySection.classList.remove("hidden");
    fetchGamesAndRooms();
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
                console.warn("輪詢失敗", error);
            }
        }, 2500);
    } catch (error) {
        showToast(error.message || "無法進入房間", true);
        enterLobby();
    }
}

function leaveRoom() {
    state.activeRoom = null;
    state.selectedCell = null;
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
    dom.roomTitle.textContent = `${room.name} ｜ ${room.gameTypeName || room.gameType}`;
    const statusText = room.started
        ? (room.status === "FINISHED" ? "對戰結束" : "對戰進行中")
        : "等待開始";
    dom.roomStatus.textContent = statusText;
    renderRoomActions(room);
    renderPlayerList(room);
    renderBoard(room);
    renderMoveHistory(room);
}

function renderRoomActions(room) {
    dom.roomActions.innerHTML = "";
    if (!state.user) {
        return;
    }
    const isHost = room.hostUserId === state.user.id;
    const playerCount = (room.playerIds || []).length;
    const alreadyStarted = room.started;
    if (isHost && !alreadyStarted) {
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
    const grid = document.createElement("div");
    grid.className = "board-grid chinese";
    for (let row = 0; row < board.length; row++) {
        for (let col = 0; col < board[row].length; col++) {
            const cell = document.createElement("div");
            cell.className = "cell";
            cell.dataset.row = row;
            cell.dataset.col = col;
            const piece = board[row][col];
            if (piece) {
                const pieceEl = document.createElement("div");
                pieceEl.className = `piece ${piece.color.toLowerCase()}`;
                pieceEl.textContent = translatePiece(piece.type, piece.color);
                cell.appendChild(pieceEl);
            }
            if (state.selectedCell && state.selectedCell.row === row && state.selectedCell.col === col) {
                cell.classList.add("highlight");
            }
            if (isPlayerTurn(room)) {
                cell.addEventListener("click", () => handleChineseCellClick(room, row, col, piece));
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
    const game = room.gameState;
    const playerIndex = room.playerIds.indexOf(state.user.id);
    if (playerIndex === -1) {
        return;
    }
    const playerColor = playerIndex === 0 ? "RED" : "BLACK";
    if (!state.selectedCell) {
        if (piece && piece.color === playerColor) {
            state.selectedCell = { row, col };
            renderBoard(room);
        }
        return;
    }

    if (piece && piece.color === playerColor) {
        state.selectedCell = { row, col };
        renderBoard(room);
        return;
    }

    const { row: fromRow, col: fromCol } = state.selectedCell;
    submitChineseMove(room, { fromRow, fromCol, toRow: row, toCol: col });
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

init();
