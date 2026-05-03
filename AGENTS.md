* after changing the code compile the app using `cd /home/user/Documents/Github/downIoads/NewPipe && ./gradlew :app:compileDebugJavaWithJavac`

* after successfully compiling the app, you may assume that the phone is connected and you want to install and launch the app on it:
    * first get the phone using `adb devices`
    * then use `adb install` to install the app we compiled earlier
    * then launch the app using `adb shell am start -n org.schabi.newpipe.debug/org.schabi.newpipe.MainActivity`

* provide the user with a `git add` command that includes all files you modified (he will run the command manually). ensure that your git add command does not just include the files of the most recent fix, it should always include all files that we fixed in this entire session