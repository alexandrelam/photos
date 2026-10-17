package io.github.alexandrelam.photos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import io.github.alexandrelam.photos.shared.ui.DarkroomTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as PhotosApp).graph
        setContent {
            CompositionLocalProvider(LocalAppGraph provides graph) {
                DarkroomTheme {
                    AppNavigation()
                }
            }
        }
    }
}
