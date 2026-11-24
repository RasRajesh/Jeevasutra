package com.example.abhayhealth.domain.usecase

import com.example.abhayhealth.domain.model.AuthState
import com.example.abhayhealth.domain.repository.AuthRepository
import com.example.abhayhealth.domain.repository.PatientProfileRepository
import com.example.abhayhealth.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class GetStartupDestinationUseCase(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val authRepository: AuthRepository,
    private val profileRepository: PatientProfileRepository,
    private val pinAuthenticationRepository: com.example.abhayhealth.domain.repository.PinAuthenticationRepository? = null
) {
    operator fun invoke(): Flow<StartupDestination> {
        return combine(
            userPreferencesRepository.isOnboardingCompleted,
            authRepository.authState
        ) { _, authState ->
            val currentUser = (authState as? AuthState.Authenticated)?.user ?: authRepository.currentUser
            if (currentUser != null) {
                val uid = currentUser.uid
                val hasProfile = profileRepository.hasProfile(uid)
                val isPinConfigured = pinAuthenticationRepository?.isPinConfigured(uid) ?: false

                when {
                    isPinConfigured -> StartupDestination.PinLogin
                    hasProfile -> StartupDestination.Main
                    else -> StartupDestination.ProfileSetup
                }
            } else {
                StartupDestination.Onboarding
            }
        }
    }
}

sealed interface StartupDestination {
    data object Onboarding : StartupDestination
    data object Welcome : StartupDestination
    data object ProfileSetup : StartupDestination
    data object PinLogin : StartupDestination
    data object Main : StartupDestination
}
