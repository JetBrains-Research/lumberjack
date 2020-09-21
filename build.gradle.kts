plugins {
    kotlin("jvm") version "1.3.72"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven(url = "https://dl.bintray.com/egor-bogomolov/astminer/")
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("io.github.vovak.astminer", "astminer-dev", "1.328")
    implementation("com.github.ajalt.clikt", "clikt", "3.0.0-rc")
}
