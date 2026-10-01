plugins { java }
group = "io.github.underconnor.passport"
version = "0.1.0-SNAPSHOT"
repositories {
    mavenCentral()
    maven("https://repo.extendedclip.com/releases/")
    maven("https://repo.papermc.io/repository/maven-public/")
}
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.129-stable")
    implementation("com.google.code.gson:gson:2.13.2")
    compileOnly("me.clip:placeholderapi:2.12.3") { isTransitive = false }
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.papermc.paper:paper-api:26.2.build.129-stable")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test { useJUnitPlatform() }
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "module-info.class", "META-INF/versions/**/module-info.class")
    manifest.attributes["Implementation-Version"] = project.version
}
dependencyLocking { lockAllConfigurations() }
