FROM eclipse-temurin:17-jdk

WORKDIR /app

# 複製後端原始碼
COPY backend/src ./src

# 複製 WebSocket library
COPY backend/lib ./lib

# 編譯（一定要加 classpath）
RUN mkdir out && \
    javac -cp "lib/*" -d out $(find src -name "*.java")

EXPOSE 10000

# 執行（一樣要加 classpath）
CMD ["java", "-cp", "out:lib/*", "com.ocgp.server.Main"]
