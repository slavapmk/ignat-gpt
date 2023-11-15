FROM gradle:jdk17-alpine
ENV GRADLE_OPTS="-Dorg.gradle.daemon=false"
COPY . /app
WORKDIR /app
#CMD gradle run --info
CMD sh -s "cd /app && ./run.bash"