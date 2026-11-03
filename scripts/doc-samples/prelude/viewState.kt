// The ViewModel-held proxy number the end-to-end sample publishes to the UI,
// and the scope it launches in. In a real ViewModel both come from
// androidx.lifecycle; at the top level of a snippet they have to be declared.
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class MutableValue<T>(var value: T)

val _proxyNumber = MutableValue("")
val viewModelScope = CoroutineScope(Dispatchers.Default)
