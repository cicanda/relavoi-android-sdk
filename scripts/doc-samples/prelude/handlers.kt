// The per-event handlers the events page calls out of its `when`. The page
// shows the dispatch, not the bodies, which is the right choice for the page
// and means the names have to come from somewhere here.
import com.relavoi.sdk.events.RelavoiEvent

fun handleSessionCreated(e: RelavoiEvent.SessionCreated) { }
fun handleSessionActivated(e: RelavoiEvent.SessionActivated) { }
fun handleSessionExpired(e: RelavoiEvent.SessionExpired) { }
fun handleCallIncoming(e: RelavoiEvent.CallIncoming) { }
fun handleCallAnswered(e: RelavoiEvent.CallAnswered) { }
fun handleCallEnded(e: RelavoiEvent.CallEnded) { }
fun handleSmsReceived(e: RelavoiEvent.SmsReceived) { }
fun handleUnknown(e: RelavoiEvent.Unknown) { }
