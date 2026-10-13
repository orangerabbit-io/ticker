package io.orangerabbit.ticker.asset

fun newSorter(sort: String?): (List<Asset>) -> List<Asset> = when (sort) {
    "alpha" -> ::sortByAlpha
    "value" -> ::sortByValue
    "user" -> ::sortByUser
    else -> ::sortByChange
}

private fun sortByAlpha(assets: List<Asset>): List<Asset> =
    assets.sortedBy { it.symbol }

private fun sortByUser(assets: List<Asset>): List<Asset> =
    assets.sortedBy { it.meta.orderIndex }

private fun sortByValue(assets: List<Asset>): List<Asset> {
    val (active, inactive) = assets.partition { it.exchange.isActive }
    return active.sortedByDescending { it.position.value } + inactive
}

private fun sortByChange(assets: List<Asset>): List<Asset> {
    val (active, inactive) = assets.partition { it.exchange.isActive }
    return active.sortedByDescending { it.quotePrice.changePercent } + inactive
}
