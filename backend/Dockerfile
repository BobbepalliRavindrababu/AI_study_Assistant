# Stage 1: Build the modular application
FROM gradle:8.2-jdk19 AS build
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
RUN ./gradlew :study-assistant-app:build -x test

# Stage 2: Run the application
FROM openjdk:19-slim
EXPOSE 8085
WORKDIR /app

# Copy the built distribution tar from stage 1
COPY --from=build /home/gradle/src/study-assistant-app/build/distributions/study-assistant-app.tar /app/
RUN tar -xf study-assistant-app.tar --strip-components=1 && rm study-assistant-app.tar

# Run the app launcher script bootstrapped by Gradle
CMD ["./bin/study-assistant-app"]
