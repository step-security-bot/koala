plugins {
    id("conventions")
}

dependencies {
    implementation(project(":jdbc"))

    implementation("org.postgresql:postgresql:42.2.23")

    testImplementation(project(":jdbc", "testArchive"))
}