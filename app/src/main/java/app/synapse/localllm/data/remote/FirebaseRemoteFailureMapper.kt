package app.synapse.localllm.data.remote

import app.synapse.localllm.domain.remote.RemoteChatException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.functions.FirebaseFunctionsException

internal fun Exception.toRemoteChatFailure(operation: String): RemoteChatException {
    val userMessage = when {
        this is FirebaseNetworkException -> "Network unavailable. Check the connection and try again."
        this is FirebaseFirestoreException && code == FirebaseFirestoreException.Code.PERMISSION_DENIED ->
            "This account is not allowed to $operation."
        this is FirebaseFirestoreException && code == FirebaseFirestoreException.Code.UNAVAILABLE ->
            "Synapse Chat is temporarily unavailable. Try again."
        this is FirebaseFunctionsException && code == FirebaseFunctionsException.Code.PERMISSION_DENIED ->
            "This account is not allowed to $operation."
        this is FirebaseFunctionsException && code == FirebaseFunctionsException.Code.UNAUTHENTICATED ->
            "Sign in again before trying to $operation."
        else -> "Could not $operation. Try again."
    }
    return RemoteChatException(userMessage, this)
}
