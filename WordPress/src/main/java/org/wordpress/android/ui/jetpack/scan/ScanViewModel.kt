package org.wordpress.android.ui.jetpack.scan

import androidx.annotation.StringRes
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.wordpress.android.R
import org.wordpress.android.fluxc.model.SiteModel
import org.wordpress.android.fluxc.model.scan.ScanStateModel
import org.wordpress.android.fluxc.store.ScanStore
import org.wordpress.android.modules.UI_THREAD
import org.wordpress.android.ui.jetpack.common.JetpackListItemState
import org.wordpress.android.ui.jetpack.common.JetpackListItemState.ActionButtonState
import org.wordpress.android.ui.jetpack.common.JetpackListItemState.ProgressState
import org.wordpress.android.ui.jetpack.scan.ScanListItemState.ThreatItemState
import org.wordpress.android.ui.jetpack.scan.ScanNavigationEvents.OpenFixThreatsConfirmationDialog
import org.wordpress.android.ui.jetpack.scan.ScanNavigationEvents.ShowThreatDetails
import org.wordpress.android.ui.jetpack.scan.ScanViewModel.UiState.Content
import org.wordpress.android.ui.jetpack.scan.builders.ScanStateListItemsBuilder
import org.wordpress.android.ui.jetpack.scan.usecases.FetchFixThreatsStatusUseCase
import org.wordpress.android.ui.jetpack.scan.usecases.FetchFixThreatsStatusUseCase.FetchFixThreatsState
import org.wordpress.android.ui.jetpack.scan.usecases.FetchScanStateUseCase
import org.wordpress.android.ui.jetpack.scan.usecases.FetchScanStateUseCase.FetchScanState
import org.wordpress.android.ui.jetpack.scan.usecases.FixThreatsUseCase
import org.wordpress.android.ui.jetpack.scan.usecases.FixThreatsUseCase.FixThreatsState
import org.wordpress.android.ui.jetpack.scan.usecases.StartScanUseCase
import org.wordpress.android.ui.jetpack.scan.usecases.StartScanUseCase.StartScanState
import org.wordpress.android.ui.pages.SnackbarMessageHolder
import org.wordpress.android.ui.utils.UiString
import org.wordpress.android.ui.utils.UiString.UiStringRes
import org.wordpress.android.ui.utils.UiString.UiStringResWithParams
import org.wordpress.android.ui.utils.UiString.UiStringText
import org.wordpress.android.viewmodel.Event
import org.wordpress.android.viewmodel.ScopedViewModel
import javax.inject.Inject
import javax.inject.Named

class ScanViewModel @Inject constructor(
    private val scanStateListItemsBuilder: ScanStateListItemsBuilder,
    private val fetchScanStateUseCase: FetchScanStateUseCase,
    private val startScanUseCase: StartScanUseCase,
    private val fixThreatsUseCase: FixThreatsUseCase,
    private val fetchFixThreatsStatusUseCase: FetchFixThreatsStatusUseCase,
    private val scanStore: ScanStore,
    @Named(UI_THREAD) private val mainDispatcher: CoroutineDispatcher
) : ScopedViewModel(mainDispatcher) {
    private var isStarted = false

    private val _uiState: MutableLiveData<UiState> = MutableLiveData()
    val uiState: LiveData<UiState> = _uiState

    private val _snackbarEvents = MediatorLiveData<Event<SnackbarMessageHolder>>()
    val snackbarEvents: LiveData<Event<SnackbarMessageHolder>> = _snackbarEvents

    private val _navigationEvents = MediatorLiveData<Event<ScanNavigationEvents>>()
    val navigationEvents: LiveData<Event<ScanNavigationEvents>> = _navigationEvents

    private val fixableThreatIds
        get() = (_uiState.value as? Content)?.items?.filterIsInstance(ThreatItemState::class.java)
            ?.filter { it.isFixable }
            ?.map { it.threatId } ?: listOf()

    lateinit var site: SiteModel

    fun start(site: SiteModel) {
        if (isStarted) {
            return
        }
        isStarted = true
        this.site = site
        fetchScanState()
    }

    private fun fetchScanState() {
        launch {
            fetchScanStateUseCase.fetchScanState(site = site)
                .collect { state ->
                    when (state) {
                        is FetchScanState.Success -> updateUiState(buildContentUiState(state.scanStateModel))
                        is FetchScanState.Failure -> TODO() // TODO ashiagr to be implemented
                    }
                }
        }
    }

    private fun startScan() {
        launch {
            startScanUseCase.startScan(site)
                .collect { state ->
                    when (state) {
                        is StartScanState.ScanningStateUpdatedInDb -> updateUiState(buildContentUiState(state.model))
                        is StartScanState.Success -> fetchScanState()
                        is StartScanState.Failure -> TODO() // TODO ashiagr to be implemented
                    }
                }
        }
    }

    private fun fixAllThreats() {
        launch {
            updateActionButtons(isVisible = false)
            when (fixThreatsUseCase.fixThreats(remoteSiteId = site.siteId, fixableThreatIds = fixableThreatIds)) {
                is FixThreatsState.Success -> {
                    updateSnackbarMessageEvent(UiStringRes(R.string.threat_fix_all_started_message))
                    fetchFixThreatsStatus(fixableThreatIds = fixableThreatIds)
                }
                is FixThreatsState.Failure.NetworkUnavailable -> {
                    updateActionButtons(isVisible = true)
                    updateSnackbarMessageEvent(UiStringRes(R.string.error_generic_network))
                }
                is FixThreatsState.Failure.RemoteRequestFailure -> {
                    updateActionButtons(isVisible = true)
                    updateSnackbarMessageEvent(UiStringRes(R.string.threat_fix_all_error_message))
                }
            }
        }
    }

    private fun fetchFixThreatsStatus(fixableThreatIds: List<Long>) {
        launch {
            @StringRes var messageRes: Int? = null
            val scanStateModel = requireNotNull(scanStore.getScanStateForSite(site))
            fetchFixThreatsStatusUseCase.fetchFixThreatsStatus(
                remoteSiteId = site.siteId,
                fixableThreatIds = fixableThreatIds
            ).collect { status ->
                var fixingThreatIds = emptyList<Long>()
                when (status) {
                    is FetchFixThreatsState.InProgress -> {
                        fixingThreatIds = status.threatIds
                    }
                    is FetchFixThreatsState.Complete -> {
                        messageRes = R.string.threat_fix_all_status_success_message
                        fetchScanState()
                    }
                    is FetchFixThreatsState.Failure.NetworkUnavailable -> {
                        messageRes = R.string.error_generic_network
                    }
                    is FetchFixThreatsState.Failure.RemoteRequestFailure -> {
                        messageRes = R.string.threat_fix_all_status_error_message
                    }
                    is FetchFixThreatsState.Failure.FixFailure -> {
                        messageRes = R.string.threat_fix_all_status_some_threats_not_fixed_error_message
                    }
                }
                updateActionButtons(isVisible = fixingThreatIds.isEmpty())
                updateFixThreatsStatusProgressBar(scanStateModel, fixingThreatIds)
                messageRes?.let { updateSnackbarMessageEvent(UiStringRes(it)) }
            }
        }
    }

    private fun onScanButtonClicked() {
        startScan()
    }

    private fun onFixAllButtonClicked() {
        updateNavigationEvent(
            OpenFixThreatsConfirmationDialog(
                title = UiStringRes(R.string.threat_fix_all_warning_title),
                message = UiStringResWithParams(
                    R.string.threat_fix_all_warning_message,
                    listOf(UiStringText("${fixableThreatIds.size}"))
                ),
                okButtonAction = this@ScanViewModel::fixAllThreats
            )
        )
    }

    private fun onThreatItemClicked(threatId: Long) {
        _navigationEvents.value = Event(ShowThreatDetails(threatId))
    }

    fun onScanStateRequestedWithMessage(@StringRes messageRes: Int) {
        updateSnackbarMessageEvent(UiStringRes(messageRes))
        fetchScanState()
    }

    fun onFixStateRequested(threatId: Long) {
        fetchFixThreatsStatus(listOf(threatId))
    }

    private fun updateActionButtons(isVisible: Boolean) {
        (_uiState.value as? Content)?.let { content ->
            val updatesContentItems = content.items.map { contentItem ->
                if (contentItem is ActionButtonState) {
                    contentItem.copy(isVisible = isVisible)
                } else {
                    contentItem
                }
            }
            updateUiState(content.copy(items = updatesContentItems))
        }
    }

    private fun updateFixThreatsStatusProgressBar(
        scanStateModel: ScanStateModel,
        fixingThreatIds: List<Long>
    ) {
        (_uiState.value as? Content)?.let { content ->
            val updatesContentItems = content.items.map { contentItem ->
                if (contentItem is ProgressState && contentItem.isIndeterminate) {
                    contentItem.copy(
                        isVisible = fixingThreatIds.isNotEmpty(),
                        progressInfoLabel = scanStateListItemsBuilder.buildFixThreatsProgressInfoLabel(
                            threats = scanStateModel.threats ?: emptyList(),
                            fixingThreatIds = fixingThreatIds
                        )
                    )
                } else {
                    contentItem
                }
            }
            updateUiState(content.copy(items = updatesContentItems))
        }
    }

    private fun updateSnackbarMessageEvent(message: UiString) {
        _snackbarEvents.value = Event(SnackbarMessageHolder(message))
    }

    private fun updateNavigationEvent(navigationEvent: ScanNavigationEvents) {
        _navigationEvents.value = Event(navigationEvent)
    }

    private fun updateUiState(contentState: Content) {
        _uiState.value = contentState
    }

    private fun buildContentUiState(model: ScanStateModel) = Content(
        scanStateListItemsBuilder.buildScanStateListItems(
            model,
            site,
            this@ScanViewModel::onScanButtonClicked,
            this@ScanViewModel::onFixAllButtonClicked,
            this@ScanViewModel::onThreatItemClicked
        )
    )

    sealed class UiState { // TODO: ashiagr add states for loading, error as needed
        data class Content(val items: List<JetpackListItemState>) : UiState()
    }
}
