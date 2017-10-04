package com.mycelium.spvmodule

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.StrictMode
import android.preference.PreferenceManager
import android.util.Log
import com.mycelium.modularizationtools.ModuleMessageReceiver
import com.mycelium.spvmodule.guava.Bip44AccountIdleService
import org.bitcoinj.core.*
import org.bitcoinj.core.Context.enableStrictMode
import org.bitcoinj.core.Context.propagate
import org.bitcoinj.crypto.LinuxSecureRandom
import org.bitcoinj.utils.Threading
import org.bitcoinj.wallet.SendRequest
import java.util.*

class SpvModuleApplication : Application(), ModuleMessageReceiver {
    var configuration: Configuration? = null
        private set
    private var activityManager: ActivityManager? = null

    private var spvServiceIntent: Intent? = null
    private var blockchainServiceCancelCoinsReceivedIntent: Intent? = null
    private var blockchainServiceResetBlockchainIntent: Intent? = null

    private lateinit var bip44AccountIdleService: Bip44AccountIdleService

    var packageInfo: PackageInfo? = null
        private set
    private val spvMessageReceiver: SpvMessageReceiver = SpvMessageReceiver(this)

    override fun onMessage(callingPackageName: String, intent: Intent) = spvMessageReceiver.onMessage(callingPackageName, intent)

    override fun onCreate() {
        INSTANCE = if (INSTANCE != null && INSTANCE !== this) {
            throw Error("Application was instanciated more than once?")
        } else {
            this
        }

        LinuxSecureRandom() // init proper random number generator

        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().permitDiskReads().permitDiskWrites().penaltyLog().build())

        Threading.throwOnLockCycles()
        enableStrictMode()
        propagate(Constants.CONTEXT)

        Log.i(LOG_TAG, "=== starting app using configuration: ${if (Constants.TEST) "test" else "prod"}, ${Constants.NETWORK_PARAMETERS.id}")

        super.onCreate()

        packageInfo = packageInfoFromContext(this)

        configuration = Configuration(PreferenceManager.getDefaultSharedPreferences(this))
        activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        spvServiceIntent = Intent(this, SpvService::class.java)
        blockchainServiceCancelCoinsReceivedIntent = Intent(SpvService.ACTION_CANCEL_COINS_RECEIVED, null, this,
                SpvService::class.java)
        blockchainServiceResetBlockchainIntent = Intent(SpvService.ACTION_ADD_ACCOUNT, null, this, SpvService::class.java)
        bip44AccountIdleService = Bip44AccountIdleService().startAsync() as Bip44AccountIdleService
    }

    fun stopBlockchainService() {
        stopService(spvServiceIntent)
    }

    fun addAccountWalletWithExtendedKey(bip39Passphrase: ArrayList<String>, creationTimeSeconds: Long,
                                        accountIndex: Int) {
        bip44AccountIdleService.addWalletAccount(bip39Passphrase, creationTimeSeconds, accountIndex)
        bip44AccountIdleService.stopAsync()
        bip44AccountIdleService.awaitTerminated()
        bip44AccountIdleService = Bip44AccountIdleService().startAsync() as Bip44AccountIdleService
    }

    fun broadcastTransaction(tx: Transaction, accountIndex: Int) {
        bip44AccountIdleService.broadcastTransaction(tx, accountIndex)
    }

    fun broadcastTransaction(sendRequest: SendRequest, accountIndex: Int) {
        bip44AccountIdleService.broadcastTransaction(sendRequest, accountIndex)
    }

    fun maxConnectedPeers(): Int =
            if (activityManager!!.memoryClass <= Constants.MEMORY_CLASS_LOWEND) {
                4
            } else {
                6
            }

    companion object {
        private var INSTANCE: SpvModuleApplication? = null

        fun getApplication(): SpvModuleApplication = INSTANCE!!

        fun packageInfoFromContext(context: Context): PackageInfo {
            try {
                return context.packageManager.getPackageInfo(context.packageName, 0)
            } catch (x: PackageManager.NameNotFoundException) {
                throw RuntimeException(x)
            }
        }

        private val LOG_TAG: String? = this::class.java.simpleName

        fun getMbwModuleName(): String = when (BuildConfig.APPLICATION_ID) {
            "com.mycelium.spvmodule_testrelease" -> "com.mycelium.testnetwallet_spore"
            "com.mycelium.spvmodule.test" -> "com.mycelium.devwallet_spore"
            else -> throw RuntimeException("No mbw module defined for BuildConfig " + BuildConfig.APPLICATION_ID)
        }

        fun doesWalletAccountExist(accountIndex: Int): Boolean =
                INSTANCE!!.doesWalletAccountExist(accountIndex)
    }

    private fun doesWalletAccountExist(accountIndex: Int): Boolean =
            bip44AccountIdleService.doesWalletAccountExist(accountIndex)
}
