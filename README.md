# Prereq

* `sudo apt install -y openjdk-17-jdk-headless`

# Build (run from NewPipe root folder)

* `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleDebug`

# Android Studio Setup

* File > Settings > Build, Execution, Deployment > Build Tools > Gradle
    * Set Gradle JDK to `/usr/lib/jvm/java-17-openjdk-amd64`
    * 'Try again'