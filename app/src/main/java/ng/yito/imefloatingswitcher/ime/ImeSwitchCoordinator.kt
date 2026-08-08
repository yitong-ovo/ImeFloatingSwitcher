package ng.yito.imefloatingswitcher.ime

object ImeSwitchCoordinator {
    fun chooseTarget(currentId: String?, primaryId: String, secondaryId: String): String = when {
        currentId == primaryId -> secondaryId
        currentId == secondaryId -> primaryId
        else -> secondaryId
    }
}
