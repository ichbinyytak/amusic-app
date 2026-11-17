import java.io.File

plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.0" apply false
}

val userHome = System.getProperty("user.home") ?: "/tmp"
layout.buildDirectory.set(File(userHome, ".cache/amusic-app-build/root"))
