import java.util.Properties

plugins {
	id("com.android.application")
	id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreProps = Properties().apply {
	val f = rootProject.file("keystore.properties")
	if (f.exists()) f.inputStream().use(::load)
}

android {
	namespace = "eus.kompaser"
	compileSdk = 37

	defaultConfig {
		applicationId = "eus.kompaser"
		minSdk = 26
		targetSdk = 36
		versionCode = 1
		versionName = "0.1"
	}

	signingConfigs {
		if (keystoreProps.isNotEmpty()) {
			create("release") {
				storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
				storePassword = keystoreProps.getProperty("storePassword")
				keyAlias = keystoreProps.getProperty("keyAlias")
				keyPassword = keystoreProps.getProperty("keyPassword")
			}
		}
	}

	buildTypes {
		release {
			isMinifyEnabled = false
			signingConfig = signingConfigs.findByName("release")
		}
	}

	buildFeatures {
		compose = true
	}

	dependenciesInfo {
		includeInApk = false
		includeInBundle = false
	}
}

dependencies {
	implementation("androidx.core:core-ktx:1.19.0")
	implementation("androidx.activity:activity-compose:1.13.0")
	implementation("androidx.compose.ui:ui:1.12.0")
	implementation("androidx.compose.foundation:foundation:1.12.0")
	implementation("androidx.compose.material3:material3:1.4.0")
	implementation("androidx.compose.material:material-icons-extended:1.7.8")
	implementation("com.pierfrancescosoffritti.androidyoutubeplayer:core:13.0.0")
}
