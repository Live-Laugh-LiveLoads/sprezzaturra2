package com.ideaforge.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ideaforge.ui.approval.ApprovalScreen
import com.ideaforge.ui.approval.PlanEditScreen
import com.ideaforge.ui.home.HomeScreen
import com.ideaforge.ui.recording.RecordingScreen
import com.ideaforge.ui.search.SearchScreen
import com.ideaforge.ui.session.DeliverableScreen
import com.ideaforge.ui.session.SessionScreen
import com.ideaforge.ui.session.StageEditScreen
import com.ideaforge.ui.settings.OnboardingScreen
import com.ideaforge.ui.settings.PromptEditScreen
import com.ideaforge.ui.settings.SettingsScreen
import com.ideaforge.ui.settings.SettingsSectionScreen

/**
 * Navigation host for the whole app. Starts at onboarding until it has been completed, then at
 * Home. [deepLink] is a route string (see [Routes]) from a notification; it is navigated to once
 * the graph exists and then acknowledged through [onDeepLinkHandled].
 */
@Composable
fun AppRoot(deepLink: String?, onDeepLinkHandled: () -> Unit) {
    val viewModel: AppRootViewModel = hiltViewModel()
    val navController = rememberNavController()
    val context = LocalContext.current

    // Notifications report progress and approvals; ask once, up front. Denial is harmless:
    // the app works, only the alerts are missing.
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            viewModel.consumeNotificationPrompt()
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    NavHost(navController = navController, startDestination = viewModel.startRoute) {
        composable(AppRootViewModel.ONBOARDING_ROUTE) {
            OnboardingScreen(
                onDone = {
                    viewModel.markOnboardingDone()
                    navController.navigate(Routes.HOME) {
                        popUpTo(AppRootViewModel.ONBOARDING_ROUTE) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onOpenRecording = { navController.navigate(Routes.RECORDING) },
                onOpenSession = { navController.navigate(Routes.session(it)) },
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.RECORDING) {
            RecordingScreen(
                onBack = { navController.popBackStack() },
                onFinished = { sessionId ->
                    navController.navigate(Routes.session(sessionId)) {
                        popUpTo(Routes.RECORDING) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.SEARCH) {
            SearchScreen(
                onBack = { navController.popBackStack() },
                onOpenSession = { sessionId, tab -> navController.navigate(Routes.session(sessionId, tab)) },
            )
        }
        composable(
            route = Routes.SESSION,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.LongType },
                navArgument("tab") { type = NavType.IntType; defaultValue = Routes.TAB_TRANSCRIPT },
            ),
        ) {
            SessionScreen(
                onBack = { navController.popBackStack() },
                onOpenApproval = { navController.navigate(Routes.approval(it)) },
                onEditStage = { sessionId, stage, round -> navController.navigate(Routes.stageEdit(sessionId, stage, round)) },
                onOpenDeliverable = { navController.navigate(Routes.deliverable(it)) },
                onEditPlan = { navController.navigate(Routes.planEdit(it)) },
            )
        }
        composable(
            route = Routes.APPROVAL,
            arguments = listOf(navArgument("sessionId") { type = NavType.LongType }),
        ) {
            ApprovalScreen(
                onBack = { navController.popBackStack() },
                onEditPlan = { navController.navigate(Routes.planEdit(it)) },
            )
        }
        composable(
            route = Routes.PLAN_EDIT,
            arguments = listOf(navArgument("planId") { type = NavType.LongType }),
        ) {
            PlanEditScreen(onDone = { navController.popBackStack() })
        }
        composable(
            route = Routes.STAGE_EDIT,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.LongType },
                navArgument("stage") { type = NavType.StringType },
                navArgument("round") { type = NavType.IntType },
            ),
        ) {
            StageEditScreen(onDone = { navController.popBackStack() })
        }
        composable(
            route = Routes.DELIVERABLE,
            arguments = listOf(navArgument("deliverableId") { type = NavType.LongType }),
        ) {
            DeliverableScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenSection = { navController.navigate(Routes.settingsSection(it)) },
            )
        }
        composable(
            route = Routes.SETTINGS_SECTION,
            arguments = listOf(navArgument("section") { type = NavType.StringType }),
        ) {
            SettingsSectionScreen(
                onBack = { navController.popBackStack() },
                onEditPrompt = { navController.navigate(Routes.promptEdit(it)) },
            )
        }
        composable(
            route = Routes.PROMPT_EDIT,
            arguments = listOf(navArgument("agent") { type = NavType.StringType }),
        ) {
            PromptEditScreen(onBack = { navController.popBackStack() })
        }
    }

    // After the NavHost call so the graph is already set when we navigate.
    LaunchedEffect(deepLink) {
        if (deepLink != null) {
            try {
                navController.navigate(deepLink) { launchSingleTop = true }
            } catch (e: IllegalArgumentException) {
                // A stale or malformed route in an old notification must not crash the app.
            }
            onDeepLinkHandled()
        }
    }
}
