FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY . .
RUN mvn clean package -pl gateway-service -am -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/gateway-service/target/*.jar app.jar
EXPOSE $(grep 'port:' gateway-service/src/main/resources/application.yml | head -1 | awk '{print $2}')
ENTRYPOINT ["java","-jar","app.jar"]
