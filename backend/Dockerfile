FROM eclipse-temurin:17-jdk

WORKDIR /app

COPY src ./src

RUN mkdir out && javac -d out $(find src -name "*.java")

EXPOSE 10000

CMD ["java", "-cp", "out", "com.ocgp.server.Main"]
