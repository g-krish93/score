package uk.co.cricrelay.mobile.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import uk.co.cricrelay.mobile.ui.AppMotion
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import uk.co.cricrelay.mobile.feature.auth.LoginScreen
import uk.co.cricrelay.mobile.feature.auth.OnboardingScreen
import uk.co.cricrelay.mobile.feature.auth.RegisterScreen
import uk.co.cricrelay.mobile.feature.home.CreateStreamScreen
import uk.co.cricrelay.mobile.feature.home.HomeScreen
import uk.co.cricrelay.mobile.feature.home.RemoteControlScreen
import uk.co.cricrelay.mobile.feature.scoring.ScoringScreen
import uk.co.cricrelay.mobile.feature.studio.PairRemoteScreen
import uk.co.cricrelay.mobile.feature.studio.StudioScreen
import uk.co.cricrelay.mobile.feature.studio.StudioViewModel

@Composable
fun CricRelayNavHost(
    startDestination: String,
    sessionExpired: Flow<Unit> = emptyFlow(),
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    // A dead session can surface from any screen's next API call — drop the whole stack to
    // login once, wherever the user is (parity with iOS RootView + cricrelaySessionExpired).
    LaunchedEffect(Unit) {
        sessionExpired.collect {
            navController.navigate(LoginRoute) {
                popUpTo(navController.graph.id) { inclusive = true }
            }
        }
    }
    NavHost(
        navController = navController,
        startDestination = when (startDestination) {
            "login" -> LoginRoute
            "onboarding" -> OnboardingRoute
            else -> HomeRoute
        },
        modifier = modifier,
        // Forward pushes slide in from the right; pops slide back out — with a soft
        // fade so screens never hard-cut.
        enterTransition = {
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(AppMotion.NavEnterMs, easing = AppMotion.EaseOut),
            ) + fadeIn(AppMotion.enterSpec(AppMotion.NavEnterMs))
        },
        exitTransition = {
            slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(AppMotion.NavExitMs, easing = AppMotion.EaseOut),
            ) + fadeOut(AppMotion.exitSpec(AppMotion.NavExitMs))
        },
        popEnterTransition = {
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(AppMotion.NavEnterMs, easing = AppMotion.EaseOut),
            ) + fadeIn(AppMotion.enterSpec(AppMotion.NavEnterMs))
        },
        popExitTransition = {
            slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(AppMotion.NavExitMs, easing = AppMotion.EaseOut),
            ) + fadeOut(AppMotion.exitSpec(AppMotion.NavExitMs))
        },
    ) {
        composable<LoginRoute> {
            LoginScreen(
                onLoggedIn = { needsOnboarding ->
                    navController.navigate(
                        if (needsOnboarding) OnboardingRoute else HomeRoute,
                    ) {
                        popUpTo(LoginRoute) { inclusive = true }
                    }
                },
                onSignUp = { navController.navigate(RegisterRoute) },
            )
        }
        composable<RegisterRoute> {
            RegisterScreen(
                onRegistered = { needsOnboarding ->
                    navController.navigate(
                        if (needsOnboarding) OnboardingRoute else HomeRoute,
                    ) {
                        popUpTo(LoginRoute) { inclusive = true }
                    }
                },
                onBackToLogin = { navController.popBackStack() },
            )
        }
        composable<OnboardingRoute> {
            OnboardingScreen(
                onComplete = {
                    navController.navigate(HomeRoute) {
                        popUpTo(OnboardingRoute) { inclusive = true }
                    }
                },
            )
        }
        composable<HomeRoute> {
            HomeScreen(
                onOpenStudio = { slug -> navController.navigate(StudioRoute(slug)) },
                onCreateStream = { mode -> navController.navigate(CreateStreamRoute(mode)) },
                onOpenRemoteControl = { navController.navigate(RemoteControlRoute) },
                onLogout = {
                    navController.navigate(LoginRoute) {
                        popUpTo(HomeRoute) { inclusive = true }
                    }
                },
            )
        }
        composable<CreateStreamRoute> { entry ->
            val route = entry.toRoute<CreateStreamRoute>()
            CreateStreamScreen(
                mode = route.mode,
                onCreated = { match ->
                    navController.navigate(StudioRoute(match.slug)) {
                        popUpTo(HomeRoute)
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable<StudioRoute>(
            // The studio "grows" out of the tapped tile: zoom + fade instead of a slide,
            // so entering the camera feels like stepping into the broadcast.
            enterTransition = {
                scaleIn(
                    initialScale = AppMotion.EnterScale,
                    animationSpec = AppMotion.enterSpec(280),
                ) + fadeIn(AppMotion.enterSpec(280))
            },
            popExitTransition = {
                scaleOut(
                    targetScale = AppMotion.ExitScale,
                    animationSpec = AppMotion.exitSpec(220),
                ) + fadeOut(AppMotion.exitSpec(220))
            },
        ) { entry ->
            val route = entry.toRoute<StudioRoute>()
            StudioScreen(
                matchSlug = route.matchSlug,
                onBack = { navController.popBackStack() },
                onOpenScoring = { slug -> navController.navigate(ScoringRoute(slug)) },
                onPairRemote = { navController.navigate(PairRemoteRoute(route.matchSlug)) },
            )
        }
        composable<PairRemoteRoute> { entry ->
            val route = entry.toRoute<PairRemoteRoute>()
            val parentEntry = remember(entry) {
                navController.getBackStackEntry(StudioRoute(route.matchSlug))
            }
            val studioViewModel: StudioViewModel = hiltViewModel(parentEntry)
            PairRemoteScreen(
                onBack = { navController.popBackStack() },
                viewModel = studioViewModel,
            )
        }
        composable<RemoteControlRoute> {
            RemoteControlScreen(onBack = { navController.popBackStack() })
        }
        composable<ScoringRoute> { entry ->
            val route = entry.toRoute<ScoringRoute>()
            ScoringScreen(
                matchSlug = route.matchSlug,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
