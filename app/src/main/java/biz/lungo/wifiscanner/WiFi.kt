package biz.lungo.wifiscanner

data class WiFi(val name: String, val level: Int) {

    override fun toString(): String {
        return "WiFi(name='$name', level=$level)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as WiFi

        if (name != other.name) return false

        return true
    }

    override fun hashCode(): Int {
        return name.hashCode()
    }
}
