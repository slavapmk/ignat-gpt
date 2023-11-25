FROM gradle:jdk17-alpine
ENV GRADLE_OPTS="-Dorg.gradle.daemon=false"
COPY . /app
WORKDIR /app
RUN rm -rf /app/build
ENTRYPOINT ./run.bash
