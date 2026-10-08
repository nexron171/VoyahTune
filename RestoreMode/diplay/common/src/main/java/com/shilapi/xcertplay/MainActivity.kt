package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shilapi.xcertplay.mfi.MfiProtocolMajorResult
import com.shilapi.xcertplay.mfi.MfiSelfCheck
import com.shilapi.xcertplay.mfi.MfiSelfCheckResult
import com.shilapi.xcertplay.transport.LinuxI2cTransport
import com.shilapi.xcertplay.ui.theme.XcertplayTheme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var status by mutableStateOf<DiagnosticStatus>(DiagnosticStatus.Idle)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLocale.enforce(this)
        enableEdgeToEdge()
        setContent {
            XcertplayTheme {
                var devicePath by remember { mutableStateOf("/dev/i2c-1") }
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    Column(
                        modifier = Modifier.padding(padding).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(getString(R.string.voyah_mfi_title))
                        OutlinedTextField(
                            value = devicePath,
                            onValueChange = { devicePath = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(getString(R.string.voyah_mfi_device)) },
                            singleLine = true,
                            enabled = status !is DiagnosticStatus.Running,
                        )
                        Button(
                            onClick = { runSelfCheck(devicePath) },
                            enabled = status !is DiagnosticStatus.Running,
                        ) {
                            Text(getString(R.string.voyah_mfi_run))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(status.message(this@MainActivity))
                        Text(getString(R.string.voyah_mfi_ch341_hint))
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun runSelfCheck(devicePath: String) {
        status = DiagnosticStatus.Running
        executor.execute {
            val next = try {
                LinuxI2cTransport.open(devicePath).use { MfiSelfCheck(it).run() }
                    .let { DiagnosticStatus.Result(it) }
            } catch (error: LinkageError) {
                DiagnosticStatus.Failure(error.message ?: getString(R.string.voyah_mfi_library_unavailable))
            } catch (error: Exception) {
                DiagnosticStatus.Failure(error.message ?: error.javaClass.simpleName)
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) status = next
            }
        }
    }
}

private sealed class DiagnosticStatus {
    data object Idle : DiagnosticStatus()
    data object Running : DiagnosticStatus()
    data class Result(val selfCheck: MfiSelfCheckResult) : DiagnosticStatus()
    data class Failure(val message: String) : DiagnosticStatus()

    fun message(context: Context): String = when (this) {
        Idle -> context.getString(R.string.voyah_mfi_idle)
        Running -> context.getString(R.string.voyah_mfi_running)
        is Failure -> context.getString(R.string.voyah_mfi_failed, message)
        is Result -> {
            val chip = selfCheck.chip ?: return if (selfCheck.discovery.interrupted) {
                context.getString(R.string.voyah_mfi_interrupted)
            } else {
                context.getString(R.string.voyah_mfi_not_found)
            }
            val major = when (val result = chip.protocolMajor) {
                is MfiProtocolMajorResult.Value -> "%d".format(result.major)
                is MfiProtocolMajorResult.MfiFailure -> result.error.message ?: result.error.javaClass.simpleName
                is MfiProtocolMajorResult.TransportFailure -> result.error.message ?: result.error.javaClass.simpleName
            }
            context.getString(
                R.string.voyah_mfi_result,
                chip.address7Bit,
                chip.deviceVersion,
                major,
            )
        }
    }
}
