FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -ntp package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl && addgroup -S billing && adduser -S billing -G billing
WORKDIR /app
COPY --from=build --chown=billing:billing /workspace/target/tech-challenge-oficina-billing-service-*.jar app.jar
USER billing
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
