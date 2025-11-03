package com.ocgp.server;

public enum GameType {
    GOBANG("Gobang", "Place five stones in a row on a 15x15 board."),
    CHINESE_CHESS("Chinese Chess", "Classic 9x10 Chinese chess with full movement rules.");

    private final String displayName;
    private final String description;

    GameType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public static GameType fromString(String value) {
        for (GameType type : values()) {
            if (type.name().equalsIgnoreCase(value) || type.displayName.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unsupported game type: " + value);
    }
}
