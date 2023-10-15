FROM bellsoft/liberica-openjre-alpine:17
COPY . /app
WORKDIR /app
CMD ./gradlew build
RUN sh -c "./gradlew run"