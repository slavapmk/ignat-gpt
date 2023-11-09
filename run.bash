if [ -f ./build/libs/IgnatGPT.jar ]; then
    java -jar ./build/libs/IgnatGPT.jar
else
    gradle shadowJar --info && java -jar ./build/libs/IgnatGPT.jar
fi