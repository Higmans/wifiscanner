package biz.lungo.wifiscanner.data

enum class ScanMode {
    Network,
    Power,
    Hybrid;

    companion object {
        private val DEFAULT = Network

        fun fromString(name: String?): ScanMode {
            ScanMode.values().forEach {
                if (name == it.name) {
                    return it
                }
            }
            return DEFAULT
        }
    }
}