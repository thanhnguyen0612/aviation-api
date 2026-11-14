FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY src src
RUN ./gradlew --no-daemon -q bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/build/libs/*.jar app.jar
RUN useradd --system --no-create-home appuser
USER appuser
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
