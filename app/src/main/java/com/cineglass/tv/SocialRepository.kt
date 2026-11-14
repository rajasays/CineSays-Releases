package com.cineglass.tv

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID

data class CineFriend(val uid: String = "", val name: String = "")
data class CineInvite(val senderId: String = "", val senderName: String = "")
data class CineRecommendation(
    val id: String = "", val titleId: Long = 0, val kind: String = "movie",
    val title: String = "", val poster: String = "", val senderId: String = "",
    val senderName: String = "", val note: String = "", val createdAt: Long = 0,
    /** Only on "sent" copies: who it went to. */
    val recipientName: String = ""
)
data class SocialState(
    val enabled: Boolean = false, val busy: Boolean = false,
    val name: String = "CineSays User", val friendCode: String = "",
    val friends: List<CineFriend> = emptyList(), val invites: List<CineInvite> = emptyList(),
    val received: List<CineRecommendation> = emptyList(), val sent: List<CineRecommendation> = emptyList(),
    val message: String = ""
)

/**
 * Optional Firebase backend. This is not a fake friends list: until the user configures
 * Firebase, this state reports that cloud sync is unavailable.
 *
 * Anonymous identity persists through app updates, but may be lost if app data is cleared.
 * For durable multi-device identity, add account linking (email/device-code sign-in) later.
 */
class SocialRepository(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(SocialState())
    val state: StateFlow<SocialState> = _state
    private var db: FirebaseFirestore? = null
    private var auth: FirebaseAuth? = null
    private var uid = ""
    private val listeners = mutableListOf<ListenerRegistration>()

    init {
        val apiKey = BuildConfig.FIREBASE_API_KEY
        val appId = BuildConfig.FIREBASE_APP_ID
        val projectId = BuildConfig.FIREBASE_PROJECT_ID
        if (apiKey.isBlank() || appId.isBlank() || projectId.isBlank()) {
            _state.value = _state.value.copy(message = "Friends needs Firebase configuration. See FIREBASE_SETUP.md.")
        } else scope.launch {
            try {
                val options = FirebaseOptions.Builder().setApiKey(apiKey).setApplicationId(appId)
                    .setProjectId(projectId).build()
                val firebaseApp = FirebaseApp.getApps(app).firstOrNull { it.name == "CineSaysSocial" }
                    ?: FirebaseApp.initializeApp(app, options, "CineSaysSocial")
                auth = FirebaseAuth.getInstance(firebaseApp)
                db = FirebaseFirestore.getInstance(firebaseApp)
                val user = auth!!.currentUser ?: auth!!.signInAnonymously().await().user
                uid = requireNotNull(user).uid
                val profile = db!!.collection("profiles").document(uid)
                val existing = profile.get().await()
                val name = existing.getString("name") ?: "CineSays User"
                val code = existing.getString("friendCode") ?: UUID.randomUUID().toString().replace("-", "").take(10).uppercase()
                if (!existing.exists()) {
                    profile.set(mapOf("name" to name, "friendCode" to code)).await()
                    db!!.collection("friendCodes").document(code).set(mapOf("uid" to uid)).await()
                }
                _state.value = _state.value.copy(enabled = true, name = name, friendCode = code, message = "")
                subscribe()
            } catch (e: Exception) {
                _state.value = _state.value.copy(message = "Cloud connection unavailable: ${e.localizedMessage}")
            }
        }
    }

    private fun subscribe() {
        val base = db!!.collection("profiles").document(uid)
        listeners.forEach { it.remove() }; listeners.clear()
        listeners += base.collection("friends").addSnapshotListener { snap, err ->
            if (err != null) { _state.value = _state.value.copy(message = err.localizedMessage ?: "Friends unavailable"); return@addSnapshotListener }
            _state.value = _state.value.copy(friends = snap?.documents.orEmpty().map { CineFriend(it.id, it.getString("name").orEmpty()) }.sortedBy { it.name })
        }
        listeners += base.collection("requests").addSnapshotListener { snap, _ ->
            _state.value = _state.value.copy(invites = snap?.documents.orEmpty().map { CineInvite(it.id, it.getString("senderName").orEmpty()) })
        }
        listeners += base.collection("received").addSnapshotListener { snap, _ ->
            _state.value = _state.value.copy(received = snap?.documents.orEmpty().map { it.toRecommendation() }.sortedByDescending { it.createdAt })
        }
        listeners += base.collection("sent").addSnapshotListener { snap, _ ->
            _state.value = _state.value.copy(sent = snap?.documents.orEmpty().map { it.toRecommendation() }.sortedByDescending { it.createdAt })
        }
    }

    private fun com.google.firebase.firestore.DocumentSnapshot.toRecommendation() = CineRecommendation(
        id, getLong("titleId") ?: 0, getString("kind") ?: "movie", getString("title") ?: "",
        getString("poster") ?: "", getString("senderId") ?: "", getString("senderName") ?: "",
        getString("note") ?: "", getLong("createdAt") ?: 0, getString("recipientName") ?: ""
    )

    fun changeName(value: String) = scope.launch {
        val name = value.trim().take(40)
        if (name.length < 2 || !requireReady()) return@launch
        try { db!!.collection("profiles").document(uid).update("name", name).await(); _state.value = _state.value.copy(name = name, message = "Display name saved") }
        catch (e: Exception) { error(e) }
    }

    fun addByCode(input: String) = scope.launch {
        if (!requireReady()) return@launch
        val code = input.trim().uppercase()
        if (code == _state.value.friendCode) { _state.value = _state.value.copy(message = "You cannot add yourself."); return@launch }
        try {
            val codeDoc = db!!.collection("friendCodes").document(code).get().await()
            val target = codeDoc.getString("uid")
            if (target.isNullOrBlank()) { _state.value = _state.value.copy(message = "Friend code not found"); return@launch }
            if (_state.value.friends.any { it.uid == target }) { _state.value = _state.value.copy(message = "Already friends"); return@launch }
            db!!.collection("profiles").document(target).collection("requests").document(uid)
                .set(mapOf("senderName" to _state.value.name, "senderId" to uid)).await()
            _state.value = _state.value.copy(message = "Friend request sent")
        } catch (e: Exception) { error(e) }
    }

    fun accept(invite: CineInvite) = scope.launch {
        if (!requireReady()) return@launch
        try {
            val otherName = db!!.collection("profiles").document(invite.senderId).get().await().getString("name") ?: invite.senderName
            val batch = db!!.batch()
            val mine = db!!.collection("profiles").document(uid)
            val theirs = db!!.collection("profiles").document(invite.senderId)
            batch.set(mine.collection("friends").document(invite.senderId), mapOf("name" to otherName))
            batch.set(theirs.collection("friends").document(uid), mapOf("name" to _state.value.name))
            batch.delete(mine.collection("requests").document(invite.senderId))
            batch.commit().await()
            _state.value = _state.value.copy(message = "Added $otherName")
        } catch (e: Exception) { error(e) }
    }

    fun decline(invite: CineInvite) = scope.launch {
        if (!requireReady()) return@launch
        try {
            db!!.collection("profiles").document(uid).collection("requests").document(invite.senderId).delete().await()
            _state.value = _state.value.copy(message = "Declined ${invite.senderName}")
        } catch (e: Exception) { error(e) }
    }

    fun recommend(item: TitleItem, recipients: Set<String>, note: String = "") = scope.launch {
        if (!requireReady()) return@launch
        val friends = _state.value.friends.filter { it.uid in recipients }
        if (friends.isEmpty()) { _state.value = _state.value.copy(message = "Choose at least one friend"); return@launch }
        try {
            val batch = db!!.batch()
            val payload = mapOf(
                "titleId" to item.id, "kind" to item.kind, "title" to item.label,
                "poster" to (item.poster ?: ""), "senderId" to uid,
                "senderName" to _state.value.name, "note" to note.take(280),
                "createdAt" to System.currentTimeMillis()
            )
            friends.forEach { friend ->
                val id = UUID.randomUUID().toString()
                batch.set(db!!.collection("profiles").document(friend.uid).collection("received").document(id), payload)
                batch.set(db!!.collection("profiles").document(uid).collection("sent").document(id), payload + mapOf("recipientName" to friend.name))
            }
            batch.commit().await()
            _state.value = _state.value.copy(message = "Recommended ${item.label} to ${friends.size} friend(s)")
        } catch (e: Exception) { error(e) }
    }

    private fun requireReady(): Boolean = if (db == null || uid.isBlank()) {
        _state.value = _state.value.copy(message = "Configure Firebase and connect to the internet first")
        false
    } else true
    private fun error(e: Exception) { _state.value = _state.value.copy(message = e.localizedMessage ?: "Request failed") }
}
