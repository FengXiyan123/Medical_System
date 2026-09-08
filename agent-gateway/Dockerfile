FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /workspace
COPY pom.xml ./
COPY agent-gateway/pom.xml agent-gateway/pom.xml
RUN mvn -pl agent-gateway -am dependency:go-offline
COPY agent-gateway/src agent-gateway/src
RUN mvn -pl agent-gateway -am package -DskipTests

FROM eclipse-temurin:21-jre

WORKDIR /app
COPY --from=build /workspace/agent-gateway/target/agent-gateway-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
