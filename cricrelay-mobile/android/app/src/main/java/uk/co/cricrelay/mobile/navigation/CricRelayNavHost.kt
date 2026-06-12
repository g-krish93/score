package uk.co.cricrelay.mobile.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import uk.co.cricrelay.mobile.feature.auth.LoginScreen
import uk.co.cricrelay.mobile.feature.auth.OnboardingScreen
import uk.co.cricrelay.mobile.feature.auth.RegisterScreen
import uk.co.cricrelay.mobile.feature.home.CreateStreamScreen
import uk.co.cricrelay.mobile.feature.home.HomeScreen
import uk.co.cricrelay.mobile.feature.pcsble.PcsBleScreen
import uk.co.cricrelay.mobile.feature.scoring.ScoringScreen
import uk.co.cricrelay.mobile.feature.studio.StudioScreen

@Composable
fun CricRelayNavHost(
    startDestination: String,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = when (startDestination) {
            "login" -> LoginRoute
            "onboarding" -> OnboardingRoute
            else -> HomeRoute
        },
        modifier = modifier,
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
                onOpenPcsBle = { navController.navigate(PcsBleRoute) },
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
        composable<StudioRoute> { entry ->
            val route = entry.toRoute<StudioRoute>()
            StudioScreen(
                matchSlug = route.matchSlug,
                onBack = { navController.popBackStack() },
                onOpenScoring = { slug -> navController.navigate(ScoringRoute(slug)) },
            )
        }
        composable<ScoringRoute> { entry ->
            val route = entry.toRoute<ScoringRoute>()
            ScoringScreen(
                matchSlug = route.matchSlug,
                onBack = { navController.popBackStack() },
            )
        }
        composable<PcsBleRoute> {
            PcsBleScreen(onBack = { navController.popBackStack() })
        }
    }
}
