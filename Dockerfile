#FROM bellsoft/liberica-openjre-alpine:17
#COPY . /app
#WORKDIR /app
#CMD ./gradlew build
#RUN sh -c "./gradlew run"
FROM gradle:jdk17-alpine
COPY . /app
WORKDIR /app
RUN gradle build
CMD gradle run
