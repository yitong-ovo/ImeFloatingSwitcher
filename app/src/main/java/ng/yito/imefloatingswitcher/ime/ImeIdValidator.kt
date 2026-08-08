package ng.yito.imefloatingswitcher.ime

object ImeIdValidator {
    private const val MAX_LENGTH = 512

    fun isValid(id: String?): Boolean {
        if (id.isNullOrBlank() || id.length > MAX_LENGTH) return false
        if (id.any { it.isWhitespace() || it.isISOControl() }) return false
        val slash = id.indexOf('/')
        return slash > 0 && slash == id.lastIndexOf('/') && slash < id.lastIndex
    }
}
