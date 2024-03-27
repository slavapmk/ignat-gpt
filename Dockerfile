FROM gradle:jdk17-alpine
ENV GRADLE_OPTS="-Dorg.gradle.daemon=false"
COPY . /app
WORKDIR /app
CMD ./gradlew run
