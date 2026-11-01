package app.cliopdf.scan

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class ClioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
    }
}
