FROM gradle:jdk17-alpine
ENV GRADLE_OPTS="-Dorg.gradle.daemon=false"
COPY . /home/gradle/app
WORKDIR /home/gradle/app
CMD ./run.bash