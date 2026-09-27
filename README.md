## NewPipe Fork

I maintain this fork myself, added a bunch of cool features:

* Negative Audio Gain (sound is too loud without this)
* Efficient fetching of data, efficient caching, instant jumps within video via double-tap gestures
* Local LLM for translation comments in any language (you manually have to download and place the model you want to use)
* Removed unneeded bloat
* Added gestures that seemed useful (e.g. click comment section icon while in comment section to scroll all the way to the top)
* SponsorBlock (skip sponsored segments without skipping intro/outro)

## Prerequisites

* `sudo apt install -y openjdk-17-jdk-headless`

## Build (run from NewPipe root folder)

* `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleDebug`

* All-in-one-command to build and install: 
`./gradlew :app:assembleDebug --no-daemon && adb install -r -d app/build/outputs/apk/debug/app-debug.apk && adb shell monkey -p org.schabi.newpipe.debug -c android.intent.category.LAUNCHER 1`

## Android Studio Setup (no need to use it but if you want to then follow this)

* File > Settings > Build, Execution, Deployment > Build Tools > Gradle
    * Set Gradle JDK to `/usr/lib/jvm/java-17-openjdk-amd64`
    * 'Try again'
