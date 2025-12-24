FROM eclipse-temurin:17-jdk

WORKDIR /app

ENV OCGP_STATIC_DIR=/app/frontend \
    OCGP_PORT=8080 \
    OCGP_WS_PORT=8091

RUN mkdir -p lib out
RUN curl -L -o lib/sqlite-jdbc.jar https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.45.1.0/sqlite-jdbc-3.45.1.0.jar \
 && curl -L -o lib/java-websocket.jar https://repo1.maven.org/maven2/org/java-websocket/Java-WebSocket/1.5.6/Java-WebSocket-1.5.6.jar \
 && curl -L -o lib/slf4j-simple.jar https://repo1.maven.org/maven2/org/slf4j/slf4j-simple/2.0.9/slf4j-simple-2.0.9.jar \
 && curl -L -o lib/slf4j-api.jar https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar

COPY src ./src
COPY ../frontend ./frontend

RUN find src -name "*.java" > sources.txt && \
    javac --add-modules jdk.httpserver -cp "lib/sqlite-jdbc.jar:lib/java-websocket.jar:lib/slf4j-simple.jar:lib/slf4j-api.jar" -d out @sources.txt

EXPOSE 8080 8091

CMD ["java", "--add-modules", "jdk.httpserver", "-cp", "out:lib/sqlite-jdbc.jar:lib/java-websocket.jar:lib/slf4j-simple.jar:lib/slf4j-api.jar", "com.ocgp.server.Main"]
