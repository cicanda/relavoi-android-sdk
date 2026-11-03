// The app's own backend, which holds the API secret and calls
// POST /v1/auth/sdk-token. Every initialize sample reaches for it.
import com.relavoi.sdk.auth.RelavoiToken

object myBackend {
    suspend fun relavoiToken(): RelavoiToken = RelavoiToken("stub", 900)
}
