// The verification page shows one snippet from inside a Fragment, where
// requireContext() is a member. The generated host is an Activity, which is
// already a Context, so this makes the snippet mean what it says.
import android.content.Context
import androidx.appcompat.app.AppCompatActivity

fun AppCompatActivity.requireContext(): Context = this
