FROM gradle:jdk17-alpine
ENV GRADLE_OPTS="-Dorg.gradle.daemon=false"
COPY . /app
WORKDIR /app
RUN export JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -Dfile.encoding=UTF-8"
RUN ./gradlew build
CMD ./gradlew run
