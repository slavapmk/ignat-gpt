FROM bellsoft/liberica-openjre-alpine:17
COPY . /app
CMD /app/gradlew build
RUN sh -c "cd /app && /app/gradlew run"