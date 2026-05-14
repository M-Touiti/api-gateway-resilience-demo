FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app
COPY . .
RUN ./mvnw clean package -pl payment-service -am -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /app/payment-service/target/*.jar app.jar
EXPOSE $(grep 'port:' payment-service/src/main/resources/application.yml | head -1 | awk '{print $2}')
ENTRYPOINT ["java","-jar","app.jar"]
