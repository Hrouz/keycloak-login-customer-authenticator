# Stage 1: Build and package the application
FROM maven:3.8.4-openjdk-17 AS builder
WORKDIR /app

# Copy only the keycloak authenticator module
COPY keycloak-login-customer-authenticator/pom.xml ./pom.xml
RUN mvn dependency:go-offline -B || true

COPY keycloak-login-customer-authenticator/src ./src

RUN mvn clean package -DskipTests -B

# Stage 2: Create the final runtime image
FROM maven:3.8.4-openjdk-17 AS runtime
WORKDIR /app

# Copy the packaged JAR from the builder stage
COPY --from=builder /app/target/keycloak-login-customer-authenticator-1.0.0.0-SNAPSHOT.jar ./app_kc_login.jar