/*
 * Copyright 2017 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.banes.chris.tivi.trakt

import android.content.SharedPreferences
import androidx.core.content.edit
import com.uwetrottmann.trakt5.TraktV2
import dagger.Lazy
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.subjects.BehaviorSubject
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.withContext
import me.banes.chris.tivi.AppNavigator
import me.banes.chris.tivi.actions.ShowTasks
import me.banes.chris.tivi.data.entities.TraktUser
import me.banes.chris.tivi.inject.ApplicationLevel
import me.banes.chris.tivi.trakt.calls.UserMeCall
import me.banes.chris.tivi.util.AppCoroutineDispatchers
import me.banes.chris.tivi.util.AppRxSchedulers
import me.banes.chris.tivi.util.NetworkDetector
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.ClientAuthentication
import net.openid.appauth.TokenResponse
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class TraktManager @Inject constructor(
    private val schedulers: AppRxSchedulers,
    private val dispatchers: AppCoroutineDispatchers,
    @ApplicationLevel private val disposables: CompositeDisposable,
    @Named("app") private val appNavigator: AppNavigator,
    private val requestProvider: Provider<AuthorizationRequest>,
    private val authService: AuthorizationService,
    private val clientAuth: Lazy<ClientAuthentication>,
    @Named("auth") private val authPrefs: SharedPreferences,
    private val userMeCall: UserMeCall,
    private val networkDetector: NetworkDetector,
    private val showTasks: ShowTasks
) {
    private val authState = BehaviorSubject.create<AuthState>()!!

    private val _state = BehaviorSubject.createDefault(TraktAuthState.LOGGED_OUT)!!
    val state: Observable<TraktAuthState>
        get() = _state

    init {
        // Observer which updates local state
        disposables += authState.observeOn(schedulers.main)
                .subscribe(::updateAuthState, Timber::e)

        // Read the auth state from prefs
        launch(dispatchers.main) {
            val state = withContext(dispatchers.disk) {
                readAuthState()
            }
            authState.onNext(state)
        }
    }

    internal fun applyToTraktClient(traktClient: TraktV2) {
        authState.value?.also {
            traktClient.accessToken(it.accessToken)
            traktClient.refreshToken(it.refreshToken)
        }
    }

    private fun updateAuthState(authState: AuthState) {
        if (authState.isAuthorized) {
            _state.onNext(TraktAuthState.LOGGED_IN)

            disposables += networkDetector.waitForConnection()
                    .subscribe({ refreshUserProfile() }, Timber::e)
        } else {
            _state.onNext(TraktAuthState.LOGGED_OUT)
        }
    }

    private fun refreshUserProfile() {
        launch {
            try {
                userMeCall.refresh(Unit)
            } catch (e: Exception) {
                Timber.e(e, "Error while refreshing user profile")
            }
        }
    }

    fun startAuth(requestCode: Int) {
        authService.performAuthorizationRequest(
                requestProvider.get(),
                appNavigator.provideAuthHandleResponseIntent(requestCode)
        )
    }

    fun onAuthResponse(response: AuthorizationResponse) {
        performTokenExchange(response)
    }

    fun onAuthException(exception: AuthorizationException) {
        Timber.d(exception, "AuthException")
    }

    fun userObservable(): Flowable<TraktUser> = userMeCall.data()

    private fun performTokenExchange(response: AuthorizationResponse) {
        authService.performTokenRequest(
                response.createTokenExchangeRequest(),
                clientAuth.get(),
                ::onTokenExchangeResponse
        )
    }

    private fun onTokenExchangeResponse(response: TokenResponse?, ex: AuthorizationException?) {
        val newState = AuthState().apply { update(response, ex) }
        // Update our local state
        authState.onNext(newState)
        // Persist auth state
        launch(dispatchers.disk) {
            persistAuthState(newState)
        }
        // Now trigger a sync of all shows
        showTasks.syncAllShows()
    }

    private fun readAuthState(): AuthState {
        val stateJson = authPrefs.getString("stateJson", null)
        return when {
            stateJson != null -> AuthState.jsonDeserialize(stateJson)
            else -> AuthState()
        }
    }

    private fun persistAuthState(state: AuthState) {
        authPrefs.edit {
            putString("stateJson", state.jsonSerializeString())
        }
    }
}
