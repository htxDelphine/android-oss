package com.kickstarter.ui.activities

import android.os.Bundle
import com.kickstarter.R
import com.kickstarter.extensions.onChange
import com.kickstarter.extensions.showSnackbar
import com.kickstarter.libs.BaseActivity
import com.kickstarter.libs.qualifiers.RequiresActivityViewModel
import com.kickstarter.libs.rx.transformers.Transformers.observeForUI
import com.kickstarter.viewmodels.ComposeMessageViewModel
import kotlinx.android.synthetic.main.activity_compose_message.*

@RequiresActivityViewModel(ComposeMessageViewModel.ViewModel::class)
class ComposeMessageActivity : BaseActivity<ComposeMessageViewModel.ViewModel>() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_compose_message)

        this.viewModel.outputs.sendButtonIsEnabled()
                .compose(bindToLifecycle())
                .compose(observeForUI())
                .subscribe { send_message_button.isEnabled = it }

        this.viewModel.outputs.success()
                .compose(bindToLifecycle())
                .compose(observeForUI())
                .subscribe { showSnackbar(message_body, "yay")}

        this.viewModel.outputs.error()
                .compose(bindToLifecycle())
                .compose(observeForUI())
                .subscribe { showSnackbar(message_body, it) }

        message_body.onChange { this.viewModel.inputs.messageBodyChanged(it)}

        send_message_button.setOnClickListener {
            this.viewModel.inputs.sendButtonClicked()
        }
    }
}