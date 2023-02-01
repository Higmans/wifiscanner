package biz.lungo.wifiscanner

import com.google.gson.annotations.SerializedName

data class MessageRequest(

    @SerializedName("chat_id") val chatId: Long,
    @SerializedName("text") val text: String,
    @SerializedName("parse_mode") val parseMode: String?

)

enum class ParseMode(val value: String) {
    HTML("HTML")
}
