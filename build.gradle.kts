plugins {
    kotlin("jvm") version "1.9.0"
    application
}

group = "ru.slavapmk.ignat"
version = "0.1"

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("io.github.kotlin-telegram-bot.kotlin-telegram-bot", "telegram", "6.1.0")

    implementation("org.ktorm:ktorm-core:3.6.0")
    implementation("org.ktorm:ktorm-support-sqlite:3.6.0")
    implementation("org.xerial:sqlite-jdbc:3.43.0.0")
}

application {
    mainClass.set("MainKt")
}