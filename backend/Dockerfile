FROM maven:3.9-eclipse-temurin-21@sha256:2b4496088e7b80ae10a8c9f74e574ea21380325a006ec684532ad6bad5bc7273 AS dependencies
WORKDIR /workspace

COPY pom.xml .
RUN mvn -B -q dependency:go-offline

FROM dependencies AS test
COPY src src
CMD ["mvn", "-B", "test"]

FROM test AS build
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre@sha256:273396ed5998598ed1091e8d72711c2d36980a0e65103859c55a4e977a41ffd3 AS runtime
WORKDIR /app

COPY --from=build /workspace/target/rag-api-0.1.0-SNAPSHOT.jar app.jar

USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
