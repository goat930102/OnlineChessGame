FROM eclipse-temurin:17-jdk

WORKDIR /app

# 複製後端原始碼
COPY backend/src ./src

# 複製前端靜態檔
COPY frontend ./frontend

# 編譯 Java
RUN mkdir out && javac -d out $(find src -name "*.java")

EXPOSE 10000

CMD ["java", "-cp", "out", "com.ocgp.server.Main"]
