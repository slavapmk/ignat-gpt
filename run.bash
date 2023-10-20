if [ -f .first ]; then
    java -jar build/libs/IgnatGPT.jar
else
    touch .first
    gradle shadowJar --info
fi