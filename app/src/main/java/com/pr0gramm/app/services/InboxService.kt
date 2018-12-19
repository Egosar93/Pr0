package com.pr0gramm.app.services

import android.content.SharedPreferences
import androidx.core.content.edit
import com.pr0gramm.app.Duration
import com.pr0gramm.app.Instant
import com.pr0gramm.app.R
import com.pr0gramm.app.api.pr0gramm.Api
import com.pr0gramm.app.feed.ContentType
import com.pr0gramm.app.util.StringException
import com.pr0gramm.app.util.logger
import rx.Observable
import rx.subjects.BehaviorSubject


/**
 * Service for receiving and sending private messages
 * as well as the combined inbox (comments and private messages)
 */
class InboxService(private val api: Api, private val preferences: SharedPreferences) {
    private val logger = logger("InboxService")

    private val unreadMessagesCount = BehaviorSubject.create<Api.InboxCounts>().toSerialized()!!

    /**
     * Gets unread messages
     */
    suspend fun pending(): List<Api.Message> {
        return api.inboxPending().await().messages
    }

    /**
     * Gets the list of inbox comments
     */
    suspend fun comments(olderThan: Instant? = null): List<Api.Message> {
        return api.inboxComments(olderThan?.epochSeconds).await().messages
    }

    /**
     * Gets all the comments a user has written
     */
    suspend fun getUserComments(user: String, contentTypes: Set<ContentType>, olderThan: Instant? = null): Api.UserComments {
        val beforeInSeconds = (olderThan ?: Instant.now().plus(Duration.days(1))).epochSeconds
        return api.userComments(user, beforeInSeconds, ContentType.combine(contentTypes)).await()
    }

    /**
     * Marks the given message as read. Also marks all messages below this id as read.
     * This will not affect the observable you get from [.unreadMessagesCount].
     */
    fun markAsRead(timestamp: Instant) {
        val updateRequired = messageIsUnread(timestamp)
        if (updateRequired) {
            logger.info {
                "Mark all messages with timestamp less than or equal to $timestamp as read"
            }

            preferences.edit {
                putLong(KEY_MAX_READ_MESSAGE_ID, timestamp.millis)
            }
        }
    }

    /**
     * Forgets all read messages. This is useful on logout.
     */
    fun forgetReadMessage() {
        preferences.edit {
            putLong(KEY_MAX_READ_MESSAGE_ID, 0)
        }
    }

    /**
     * Returns true if the given message was already read
     * according to [.markAsRead].
     */
    fun messageIsUnread(message: Api.Message): Boolean {
        return messageIsUnread(message.creationTime)
    }

    fun messageIsUnread(timestamp: Instant): Boolean {
        return timestamp.millis > preferences.getLong(KEY_MAX_READ_MESSAGE_ID, 0)
    }

    /**
     * Returns an observable that produces the number of unread messages.
     */
    fun unreadMessagesCount(): Observable<Api.InboxCounts> {
        return unreadMessagesCount
    }

    fun publishUnreadMessagesCount(unreadCount: Api.InboxCounts) {
        unreadMessagesCount.onNext(unreadCount)
    }

    /**
     * Sends a private message to a receiver
     */
    suspend fun send(receiverId: Long, message: String) {
        api.sendMessage(null, message, receiverId).await()
    }

    /**
     * Sends a private message to a receiver
     */
    suspend fun send(recipient: String, message: String): Api.ConversationMessages {
        val response = api.sendMessage(null, message, recipient).await()
        if (response.error == "senderIsRecipient") {
            throw StringException(R.string.error_senderIsRecipient)
        }
        return response
    }

    suspend fun listConversations(olderThan: Instant? = null): Api.Conversations {
        return api.listConversations(olderThan?.epochSeconds).await()
    }

    suspend fun messagesInConversation(name: String, olderThan: Instant? = null): Api.ConversationMessages {
        return api.messagesWith(name, olderThan?.epochSeconds).await()
    }

    companion object {
        private val KEY_MAX_READ_MESSAGE_ID = "InboxService.maxReadMessageId"
    }
}
