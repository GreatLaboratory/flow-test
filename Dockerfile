# 1) 프론트 빌드 → dist
FROM node:22-alpine AS frontend
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# 2) 백엔드 빌드 — 프론트 dist를 static/에 넣어 Spring이 같은 출처로 서빙(CORS 없음, 배포 1개)
FROM eclipse-temurin:21-jdk AS backend
WORKDIR /backend
COPY backend/gradlew backend/settings.gradle backend/build.gradle ./
COPY backend/gradle gradle
RUN ./gradlew --no-daemon -q dependencies > /dev/null   # 의존성 레이어 캐시
COPY backend/src src
COPY --from=frontend /frontend/dist src/main/resources/static
RUN ./gradlew --no-daemon -q bootJar

# 3) 실행 — JRE만, non-root
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app \
    && mkdir -p /data/uploads \
    && chown app /data/uploads
COPY --from=backend /backend/build/libs/extblock-0.0.1-SNAPSHOT.jar /app/app.jar
USER app
EXPOSE 8080
# 컨테이너 메모리 한도의 75%를 힙 상한으로 (기본값 25%는 작은 컨테이너에서 너무 적음)
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
