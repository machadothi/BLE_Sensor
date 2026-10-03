package com.machadothi.blesensor.ui.navigation

import kotlinx.serialization.Serializable

object NavRoutes {
    @Serializable
    data object Permissions

    @Serializable
    data object Scan

    @Serializable
    data class Board(val address: String, val name: String)
}
