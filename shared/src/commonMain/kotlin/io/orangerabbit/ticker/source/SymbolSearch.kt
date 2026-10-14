package io.orangerabbit.ticker.source

enum class SearchResultSource { YAHOO, COINBASE }
data class SymbolSearchResult(val symbol: String, val name: String, val exchange: String, val source: SearchResultSource)
