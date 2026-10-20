version = 2

cloudstream {
    description = "Movies and TV series from movy.sx and NetMirror"
    authors = listOf("opencode")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    language = "en"
    requiresResources = false
    iconUrl = "https://www.movy.sx/favicon.ico"
}

android {
    namespace = "com.movy"
}

dependencies {
    compileOnly("org.jspecify:jspecify:1.0.0")
    testImplementation("junit:junit:4.13.2")
}
