plugins {
    kotlin("jvm") version "1.9.0"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("org.jetbrains.kotlin.kapt") version "1.6.0"
    application
}

group = "ru.slavapmk.ignat"
version = "0.1"

tasks.withType<Jar> {
    archiveFileName.set("${project.name}.jar")
}

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

val exposedVersion: String = "0.44.0"

dependencies {
    implementation("io.github.kotlin-telegram-bot.kotlin-telegram-bot", "telegram", "6.1.0")

    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
//    implementation("org.jetbrains.exposed:exposed-crypt:$exposedVersion")
//    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
//    implementation("org.jetbrains.exposed:exposed-jodatime:$exposedVersion")
//    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")
//    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:$exposedVersion")
//    implementation("org.jetbrains.exposed:exposed-json:$exposedVersion")
//    implementation("org.jetbrains.exposed:exposed-money:$exposedVersion")
    implementation("org.xerial:sqlite-jdbc:3.43.0.0")

    implementation("com.squareup.retrofit2", "retrofit", "2.9.0")
    implementation("io.reactivex.rxjava3", "rxkotlin", "3.0.1")
    implementation("com.squareup.okhttp3:logging-interceptor:3.9.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.squareup.retrofit2:converter-gson:2.3.0")
    implementation("com.squareup.retrofit2:adapter-rxjava3:2.9.0")

    implementation("org.apache.logging.log4j:log4j-core:2.20.0")
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl:2.20.0")

    implementation("org.commonmark:commonmark:0.20.0")
}

application {
    mainClass.set("ru.slavapmk.ignat.MainKt")
}