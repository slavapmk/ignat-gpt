if [ -f build/libs/IgnatGPT.jar ]; then
    java -jar build/libs/IgnatGPT.jar
else
    gradle shadowJar --info
fi
