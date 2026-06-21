import com.android.build.api.dsl.ApplicationExtension

dependencies {
    compileOnly(libs.morphe.extensions.library)
}

configure<ApplicationExtension> {
    namespace = "app.morphe.extension.instagram"

    defaultConfig {
        minSdk = 26
    }
}
