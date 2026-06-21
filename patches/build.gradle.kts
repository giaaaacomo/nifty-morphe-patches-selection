group = "app.morphe"

patches {
    about {
        name = "giaaaacomo's nifty patches selection"
        description = "Personal Morphe patch selection by giaaaacomo"
        source = "https://github.com/giaaaacomo/nifty-morphe-patches-selection"
        author = "giaaaacomo"
        contact = "na"
        website = "https://github.com/giaaaacomo"
        license = "GNU General Public License v3.0, with additional GPL section 7 requirements"
    }
}

dependencies {
    // Used by JsonGenerator.
    implementation(libs.gson)

    // Required due to smali, or build fails. Can be removed once smali is bumped.
    implementation(libs.guava)

    implementation(libs.morphe.patches.library)

    // Android API stubs defined here.
    compileOnly(project(":patches:stub"))
}

tasks {
    jar {
        exclude(
            "addresources/**",
            "app/morphe/patches/music/**",
            "app/morphe/patches/reddit/**",
            "app/morphe/patches/shared/**",
            "app/morphe/patches/youtube/**",
            "app/morphe/patches/all/misc/debugging/**",
            "app/morphe/patches/all/misc/fix/**",
            "app/morphe/patches/all/misc/hermes/**",
            "app/morphe/patches/all/misc/hex/**",
            "app/morphe/patches/all/misc/network/**",
            "app/morphe/patches/all/misc/packagename/**",
            "app/morphe/patches/all/misc/resources/**",
            "app/morphe/patches/all/misc/string/**",
            "app/morphe/patches/all/misc/transformation/**",
            "app/morphe/patches/all/misc/updates/**",
        )
    }

    register<JavaExec>("checkStringResources") {
        description = "Checks resource strings for invalid formatting"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("app.morphe.patches.util.resource.CheckStringResourcesKt")
    }

    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("app.morphe.util.PatchListGeneratorKt")
    }
    // Used by gradle-semantic-release-plugin.
    publish {
        dependsOn("generatePatchesList")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs = listOf("-Xcontext-parameters")
    }
}
