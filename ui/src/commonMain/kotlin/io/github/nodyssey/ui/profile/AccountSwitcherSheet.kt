package io.github.nodyssey.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.nodyssey.data.session.Account
import io.github.nodyssey.data.session.AccountRepository
import io.github.nodyssey.data.session.SessionRepository
import io.github.nodyssey.data.session.SwitchResult
import io.github.nodyssey.di.AppContainer
import io.github.nodyssey.ui.common.PlazaSheet
import io.github.nodyssey.ui.resources.Res
import io.github.nodyssey.ui.resources.account_switch_add
import io.github.nodyssey.ui.resources.account_switch_delete
import io.github.nodyssey.ui.resources.account_switch_delete_body
import io.github.nodyssey.ui.resources.account_switch_delete_title
import io.github.nodyssey.ui.resources.account_switch_expired
import io.github.nodyssey.ui.resources.account_switch_relogin
import io.github.nodyssey.ui.resources.account_switch_subtitle
import io.github.nodyssey.ui.resources.account_switch_switching
import io.github.nodyssey.ui.resources.account_switch_title
import io.github.nodyssey.ui.resources.action_cancel
import io.github.plaza.designsys.component.UserAvatar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** 账号切换器 BottomSheet 的 UI 状态。 */
data class AccountSwitcherUiState(
    val accounts: List<Account> = emptyList(),
    val switchingId: String? = null,
    val switchError: String? = null,
)

class AccountSwitcherViewModel(
    private val accounts: AccountRepository,
    private val session: SessionRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AccountSwitcherUiState())
    val uiState: StateFlow<AccountSwitcherUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(accounts.accounts, accounts.activeAccountId, session.state) { list, _, _ -> list }
                .collect { list ->
                    _uiState.update { it.copy(accounts = list) }
                }
        }
    }

    fun switchTo(accountId: String, onSwitched: () -> Unit) {
        if (_uiState.value.switchingId != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(switchingId = accountId, switchError = null) }
            when (accounts.switchTo(accountId)) {
                SwitchResult.Switched, SwitchResult.AlreadyActive -> onSwitched()

                SwitchResult.NoSuchAccount -> Unit

                is SwitchResult.SessionExpired ->
                    _uiState.update { it.copy(switchError = accountId) }
            }
            _uiState.update { it.copy(switchingId = null) }
        }
    }

    fun removeAccount(accountId: String) {
        viewModelScope.launch {
            accounts.removeAccount(accountId)
        }
    }

    fun consumeSwitchError() {
        _uiState.update { it.copy(switchError = null) }
    }

    /**
     * "添加账号"：先把当前会话存盘，再进登录屏。
     * 顺序不能反 —— 登录成功的那一刻罐子里就是新账号的 cookie 了。
     */
    fun checkpointThenAdd(onDone: () -> Unit) {
        viewModelScope.launch {
            accounts.checkpointActiveAccount()
            onDone()
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    AccountSwitcherViewModel(
                        accounts = container.accountRepository,
                        session = container.sessionRepository,
                    )
                }
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSwitcherSheet(
    viewModel: AccountSwitcherViewModel,
    onAddAccount: () -> Unit,
    onReLogin: (accountId: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmingDelete by remember { mutableStateOf<Account?>(null) }

    PlazaSheet(
        onDismiss = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        title = stringResource(Res.string.account_switch_title),
        subtitle = stringResource(Res.string.account_switch_subtitle),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(state.accounts, key = { it.id }) { account ->
                AccountRow(
                    account = account,
                    switching = state.switchingId == account.id,
                    onSwitch = { viewModel.switchTo(account.id, onDismiss) },
                    onReLogin = { onReLogin(account.id) },
                    onDelete = { confirmingDelete = account },
                )
            }
            item {
                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.checkpointThenAdd(onAddAccount) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.account_switch_add),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }

    confirmingDelete?.let { account ->
        AlertDialog(
            onDismissRequest = { confirmingDelete = null },
            title = { Text(stringResource(Res.string.account_switch_delete_title)) },
            text = { Text(stringResource(Res.string.account_switch_delete_body, account.displayName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeAccount(account.id)
                        confirmingDelete = null
                    },
                ) {
                    Text(stringResource(Res.string.account_switch_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = null }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }

    // 切换后会话过期 —— 交给调用方打开一键重登，sheet 先收起来。
    state.switchError?.let { expiredId ->
        viewModel.consumeSwitchError()
        onReLogin(expiredId)
    }
}

@Composable
private fun AccountRow(
    account: Account,
    switching: Boolean,
    onSwitch: () -> Unit,
    onReLogin: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
        modifier
            .fillMaxWidth()
            .clickable(enabled = !account.isActive && !switching, onClick = onSwitch)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        UserAvatar(url = account.avatarUrl, name = account.displayName, size = 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = account.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
            )
            if (account.isExpired) {
                Text(
                    text = stringResource(Res.string.account_switch_expired),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        when {
            switching -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)

            account.isActive -> Text(
                text = "●",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )

            account.isExpired -> TextButton(onClick = onReLogin) {
                Text(stringResource(Res.string.account_switch_relogin))
            }

            else -> IconButton(onClick = onDelete) {
                Text(
                    text = stringResource(Res.string.account_switch_delete),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (switching) {
            Text(
                text = stringResource(Res.string.account_switch_switching),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
