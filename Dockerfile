FROM eclipse-temurin:17-jdk-jammy AS build

WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw

COPY src/ src/
RUN ./mvnw --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:17-jre-jammy AS runtime

WORKDIR /app

COPY --from=build /workspace/target/literary-oracle-0.0.1-SNAPSHOT.jar app.jar

ENV JAVA_TOOL_OPTIONS="-XX:+UseSerialGC -XX:MaxRAMPercentage=65.0 -XX:InitialRAMPercentage=10.0 -XX:+ExitOnOutOfMemoryError"

EXPOSE 10000

ENTRYPOINT ["java", "-jar", "app.jar"]
