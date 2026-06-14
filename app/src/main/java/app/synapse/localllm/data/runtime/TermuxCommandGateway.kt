package app.synapse.localllm.data.runtime

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import app.synapse.localllm.domain.ids.SynapseIdFactory
import app.synapse.localllm.domain.runtime.RuntimeStartReceipt
import app.synapse.localllm.domain.runtime.RuntimeStartStatus
import app.synapse.localllm.domain.runtime.StartLlamaServerCommand
import app.synapse.localllm.domain.time.SynapseClock

class TermuxCommandGateway(
    context: Context,
    private val idFactory: SynapseIdFactory,
    private val clock: SynapseClock,
) {
    private val applicationContext = context.applicationContext

    fun startLlamaServer(command: StartLlamaServerCommand): RuntimeStartReceipt {
        val requestedAt = clock.now()

        if (applicationContext.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE_NAME) == null) {
            return RuntimeStartReceipt(
                id = idFactory.createReceiptId(),
                status = RuntimeStartStatus.TERMUX_UNAVAILABLE,
                requestedAt = requestedAt,
                message = "Termux is not installed or is hidden by package visibility.",
            )
        }

        val permissionState = ContextCompat.checkSelfPermission(applicationContext, TERMUX_PERMISSION)
        if (permissionState != PackageManager.PERMISSION_GRANTED) {
            return RuntimeStartReceipt(
                id = idFactory.createReceiptId(),
                status = RuntimeStartStatus.TERMUX_PERMISSION_MISSING,
                requestedAt = requestedAt,
                message = "Grant Synapse the Termux RUN_COMMAND permission in Android settings.",
            )
        }

        val intent = Intent().apply {
            setClassName(TERMUX_PACKAGE_NAME, TERMUX_RUN_COMMAND_SERVICE_NAME)
            action = TERMUX_RUN_COMMAND_ACTION
            putExtra(TERMUX_EXTRA_COMMAND_PATH, command.commandPath)
            putExtra(TERMUX_EXTRA_ARGUMENTS, command.arguments.toTypedArray())
            putExtra(TERMUX_EXTRA_WORKDIR, command.workingDirectory)
            putExtra(TERMUX_EXTRA_BACKGROUND, command.runInBackground)
            putExtra(TERMUX_EXTRA_SESSION_ACTION, TERMUX_SESSION_NEW)
            putExtra(TERMUX_EXTRA_COMMAND_LABEL, "Start Synapse local LLM")
            putExtra(
                TERMUX_EXTRA_COMMAND_DESCRIPTION,
                "Starts llama-server for the Synapse Android app.",
            )
        }

        return try {
            val componentName = applicationContext.startService(intent)
            if (componentName == null) {
                RuntimeStartReceipt(
                    id = idFactory.createReceiptId(),
                    status = RuntimeStartStatus.FAILED,
                    requestedAt = requestedAt,
                    message = "Android did not accept the Termux run-command service start.",
                )
            } else {
                RuntimeStartReceipt(
                    id = idFactory.createReceiptId(),
                    status = RuntimeStartStatus.SENT_TO_TERMUX,
                    requestedAt = requestedAt,
                    message = "llama-server start command was sent to Termux.",
                )
            }
        } catch (exception: SecurityException) {
            RuntimeStartReceipt(
                id = idFactory.createReceiptId(),
                status = RuntimeStartStatus.TERMUX_PERMISSION_MISSING,
                requestedAt = requestedAt,
                message = exception.message ?: "Termux rejected the RUN_COMMAND permission.",
            )
        } catch (exception: RuntimeException) {
            RuntimeStartReceipt(
                id = idFactory.createReceiptId(),
                status = RuntimeStartStatus.FAILED,
                requestedAt = requestedAt,
                message = exception.message ?: "Termux command start failed.",
            )
        }
    }

    private companion object {
        const val TERMUX_PACKAGE_NAME = "com.termux"
        const val TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND"
        const val TERMUX_RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
        const val TERMUX_RUN_COMMAND_SERVICE_NAME = "com.termux.app.RunCommandService"
        const val TERMUX_EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        const val TERMUX_EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        const val TERMUX_EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        const val TERMUX_EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        const val TERMUX_EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
        const val TERMUX_EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
        const val TERMUX_EXTRA_COMMAND_DESCRIPTION = "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION"
        const val TERMUX_SESSION_NEW = "0"
    }
}
