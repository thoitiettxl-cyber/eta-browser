package com.thoitiettxl.eta

import android.app.Application
import android.webkit.WebView

/** Configures WebView drawing before the standalone browser creates its shared instance. */
class EtaBrowserApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        WebView.enableSlowWholeDocumentDraw()
    }
}
