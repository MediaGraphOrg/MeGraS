# Use a base image with Java and Gradle
FROM gradle:7.6.1-jdk17 AS builder

# Copy the source code to the container
COPY . /home/gradle/project

# Build the application
RUN gradle -q build --no-daemon

# Use a minimal runtime image
FROM eclipse-temurin:17-jre-focal

# Copy the built application from the builder stage
COPY --from=builder /home/gradle/project/build/libs/MeGraS-0.1-SNAPSHOT.jar /app/MeGraS-0.1-SNAPSHOT.jar

# Expose the port that your application listens on (e.g., 8080)
EXPOSE 8080

# Set the entrypoint to run the application
ENTRYPOINT ["java", "-jar", "/app/MeGraS-0.1-SNAPSHOT.jar"]