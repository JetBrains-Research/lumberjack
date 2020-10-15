plugins {
    kotlin("jvm") version "1.3.72"
    id("application")
}

group = "org.jetbrains.research"
version = "1.0-SNAPSHOT"

application {
    mainClassName = "org.jetbrains.research.lumberjack.MainKt"
}

repositories {
    mavenCentral()
    maven(url = "https://dl.bintray.com/egor-bogomolov/astminer/")
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("io.github.vovak.astminer", "astminer", "0.6")
    implementation("com.github.ajalt.clikt", "clikt", "3.0.1")
    implementation("com.google.code.gson", "gson", "2.8.6")
}
