plugins { java }
group = "ca.saboor"
version = "1.4.0"
repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}
java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }
val mockitoAgent = configurations.create("mockitoAgent")
dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.10-R0.1-SNAPSHOT")
    testImplementation("io.papermc.paper:paper-api:1.21.10-R0.1-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.mockito:mockito-core:5.15.2")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.76.1")
    mockitoAgent("org.mockito:mockito-core:5.15.2") { isTransitive = false }
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8"; options.release.set(21) }
tasks.test { useJUnitPlatform(); doFirst { jvmArgs("-javaagent:${mockitoAgent.singleFile}") } }
tasks.processResources { filesMatching("plugin.yml") { expand("version" to project.version) } }
tasks.jar { archiveBaseName.set("Lumigames") }
