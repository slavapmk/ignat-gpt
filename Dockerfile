FROM gradle:jdk17-alpine
ENV GRADLE_OPTS="-Dorg.gradle.daemon=false"
COPY . /app
WORKDIR /app
RUN gradle shadowJar --info
CMD sh -c "java -jar build/libs/IgnatGPT.jar"
