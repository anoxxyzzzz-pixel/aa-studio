package aa.projek.mp.max

import android.app.Application
import android.content.Context
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass

class App : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Dibutuhkan libadb-android untuk memakai Conscrypt bawaan sistem (pairing/TLS debug nirkabel).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { HiddenApiBypass.addHiddenApiExemptions("L") } catch (_: Throwable) {}
        }
    }
}
