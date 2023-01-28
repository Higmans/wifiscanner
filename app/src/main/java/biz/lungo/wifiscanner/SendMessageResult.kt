package biz.lungo.wifiscanner

import com.google.gson.annotations.SerializedName

data class SendMessageResult(

    @SerializedName("ok") var ok: Boolean,
    @SerializedName("result") var result: Result

)

data class Result(

    @SerializedName("message_id") var messageId: Long,
    @SerializedName("from") var from: From,
    @SerializedName("chat") var chat: Chat,
    @SerializedName("date") var date: Long,
    @SerializedName("text") var text: String

)

data class Chat(

    @SerializedName("id") var id: Long,
    @SerializedName("first_name") var firstName: String? = null,
    @SerializedName("username") var username: String? = null,
    @SerializedName("type") var type: String? = null

)

data class From(

    @SerializedName("id") var id: Long? = null,
    @SerializedName("is_bot") var isBot: Boolean? = null,
    @SerializedName("first_name") var firstName: String? = null,
    @SerializedName("username") var username: String? = null,
    @SerializedName("language_code") var languageCode: String? = null

)