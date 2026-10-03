package com.machadothi.blesensor.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.machadothi.blesensor.ui.screen.board.BoardScreen
import com.machadothi.blesensor.ui.screen.permissions.PermissionsScreen
import com.machadothi.blesensor.ui.screen.scan.ScanScreen

private const val TRANSITION_MS = 380

@Composable
fun AppNavHost(startWithPermissions: Boolean, modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = if (startWithPermissions) NavRoutes.Permissions else NavRoutes.Scan,
        modifier = modifier,
        enterTransition = { slideIntoContainer(SlideDirection.Start, tween(TRANSITION_MS)) + fadeIn(tween(TRANSITION_MS)) },
        exitTransition = { slideOutOfContainer(SlideDirection.Start, tween(TRANSITION_MS)) + fadeOut(tween(TRANSITION_MS)) },
        popEnterTransition = { slideIntoContainer(SlideDirection.End, tween(TRANSITION_MS)) + fadeIn(tween(TRANSITION_MS)) },
        popExitTransition = { slideOutOfContainer(SlideDirection.End, tween(TRANSITION_MS)) + fadeOut(tween(TRANSITION_MS)) },
    ) {
        composable<NavRoutes.Permissions> {
            PermissionsScreen(onGranted = {
                navController.navigate(NavRoutes.Scan) { popUpTo(NavRoutes.Permissions) { inclusive = true } }
            })
        }
        composable<NavRoutes.Scan> {
            ScanScreen(onBoardSelected = { board ->
                navController.navigate(NavRoutes.Board(board.address, board.name))
            })
        }
        composable<NavRoutes.Board> {
            BoardScreen(onBack = { navController.popBackStack() })
        }
    }
}
