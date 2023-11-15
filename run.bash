if [ ! -f ./build/libs/app.jar ]; then
    gradle shadowJar --info
fi
java -jar ./build/libs/app.jar
