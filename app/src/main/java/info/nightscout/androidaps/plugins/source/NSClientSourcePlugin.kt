package info.nightscout.androidaps.plugins.source

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.R
import info.nightscout.androidaps.database.AppRepository
import info.nightscout.androidaps.database.entities.GlucoseValue
import info.nightscout.androidaps.database.transactions.CgmSourceTransaction
import info.nightscout.androidaps.interfaces.ActivePlugin
import info.nightscout.androidaps.interfaces.BgSource
import info.nightscout.androidaps.interfaces.Config
import info.nightscout.androidaps.interfaces.NsClient
import info.nightscout.androidaps.interfaces.PluginBase
import info.nightscout.androidaps.interfaces.PluginDescription
import info.nightscout.androidaps.interfaces.PluginType
import info.nightscout.androidaps.interfaces.ResourceHelper
import info.nightscout.androidaps.plugins.bus.RxBus
import info.nightscout.androidaps.plugins.general.overview.events.EventDismissNotification
import info.nightscout.androidaps.plugins.general.overview.notifications.Notification
import info.nightscout.androidaps.plugins.sync.nsShared.events.EventNSClientNewLog
import info.nightscout.androidaps.plugins.sync.nsclient.data.NSSgv
import info.nightscout.androidaps.receivers.DataWorkerStorage
import info.nightscout.androidaps.utils.DateUtil
import info.nightscout.androidaps.utils.T
import info.nightscout.androidaps.utils.XDripBroadcast
import info.nightscout.sdk.localmodel.entry.NSSgvV3
import info.nightscout.shared.logging.AAPSLogger
import info.nightscout.shared.logging.LTag
import info.nightscout.shared.sharedPreferences.SP
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NSClientSourcePlugin @Inject constructor(
    injector: HasAndroidInjector,
    rh: ResourceHelper,
    aapsLogger: AAPSLogger,
    config: Config
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.BGSOURCE)
        .fragmentClass(BGSourceFragment::class.java.name)
        .pluginIcon(R.drawable.ic_nsclient_bg)
        .pluginName(R.string.nsclientbg)
        .shortName(R.string.nsclientbgshort)
        .description(R.string.description_source_ns_client),
    aapsLogger, rh, injector
), BgSource {

    private var lastBGTimeStamp: Long = 0
    private var isAdvancedFilteringEnabled = false

    init {
        if (config.NSCLIENT) {
            pluginDescription
                .alwaysEnabled(true)
                .setDefault()
        }
    }

    override fun advancedFilteringSupported(): Boolean {
        return isAdvancedFilteringEnabled
    }

    override fun shouldUploadToNs(glucoseValue: GlucoseValue): Boolean = false

    private fun detectSource(glucoseValue: GlucoseValue) {
        if (glucoseValue.timestamp > lastBGTimeStamp) {
            isAdvancedFilteringEnabled = arrayOf(
                GlucoseValue.SourceSensor.DEXCOM_NATIVE_UNKNOWN,
                GlucoseValue.SourceSensor.DEXCOM_G6_NATIVE,
                GlucoseValue.SourceSensor.DEXCOM_G5_NATIVE,
                GlucoseValue.SourceSensor.DEXCOM_G6_NATIVE_XDRIP,
                GlucoseValue.SourceSensor.DEXCOM_G5_NATIVE_XDRIP
            ).any { it == glucoseValue.sourceSensor }
            lastBGTimeStamp = glucoseValue.timestamp
        }
    }

    // cannot be inner class because of needed injection
    class NSClientSourceWorker(
        context: Context,
        params: WorkerParameters
    ) : Worker(context, params) {

        @Inject lateinit var nsClientSourcePlugin: NSClientSourcePlugin
        @Inject lateinit var injector: HasAndroidInjector
        @Inject lateinit var aapsLogger: AAPSLogger
        @Inject lateinit var sp: SP
        @Inject lateinit var rxBus: RxBus
        @Inject lateinit var dateUtil: DateUtil
        @Inject lateinit var dataWorkerStorage: DataWorkerStorage
        @Inject lateinit var repository: AppRepository
        @Inject lateinit var xDripBroadcast: XDripBroadcast
        @Inject lateinit var activePlugin: ActivePlugin

        init {
            (context.applicationContext as HasAndroidInjector).androidInjector().inject(this)
        }

        private fun toGv(jsonObject: JSONObject): CgmSourceTransaction.TransactionGlucoseValue? {
            val sgv = NSSgv(jsonObject)
            return CgmSourceTransaction.TransactionGlucoseValue(
                timestamp = sgv.mills ?: return null,
                value = sgv.mgdl?.toDouble() ?: return null,
                noise = null,
                raw = sgv.filtered?.toDouble() ?: sgv.mgdl?.toDouble(),
                trendArrow = GlucoseValue.TrendArrow.fromString(sgv.direction),
                nightscoutId = sgv.id,
                sourceSensor = GlucoseValue.SourceSensor.fromString(sgv.device)
            )
        }

        private fun toGv(sgv: NSSgvV3): CgmSourceTransaction.TransactionGlucoseValue {
            return CgmSourceTransaction.TransactionGlucoseValue(
                timestamp = sgv.date,
                value = sgv.sgv,
                noise = sgv.noise?.toDouble(),
                raw = sgv.filtered ?: sgv.sgv,
                trendArrow = GlucoseValue.TrendArrow.fromString(sgv.direction.nsName),
                nightscoutId = sgv.identifier,
                sourceSensor = GlucoseValue.SourceSensor.fromString(sgv.device),
                isValid = sgv.isValid
            )
        }

        @Suppress("SpellCheckingInspection")
        override fun doWork(): Result {
            var ret = Result.success()
            var processed = 0
            val sgvs = dataWorkerStorage.pickupObject(inputData.getLong(DataWorkerStorage.STORE_KEY, -1))
                ?: return Result.failure(workDataOf("Error" to "missing input data"))

            if (!nsClientSourcePlugin.isEnabled() && !sp.getBoolean(R.string.key_ns_receive_cgm, false))
                return Result.success(workDataOf("Result" to "Sync not enabled"))

            var latestDateInReceivedData: Long = 0
            aapsLogger.debug(LTag.BGSOURCE, "Received NS Data: $sgvs")
            val glucoseValues = mutableListOf<CgmSourceTransaction.TransactionGlucoseValue>()

            try {
                if (sgvs is JSONArray) { // V1 client
                    xDripBroadcast.sendSgvs(sgvs)

                    for (i in 0 until sgvs.length()) {
                        val sgv = toGv(sgvs.getJSONObject(i)) ?: continue
                        if (sgv.timestamp < dateUtil.now() && sgv.timestamp > latestDateInReceivedData) latestDateInReceivedData = sgv.timestamp
                        glucoseValues += sgv
                    }

                } else if (sgvs is List<*>) { // V3 client
//                xDripBroadcast.sendSgvs(sgvs)

                    for (i in 0 until sgvs.size) {
                        val sgv = toGv(sgvs[i] as NSSgvV3)
                        if (sgv.timestamp < dateUtil.now() && sgv.timestamp > latestDateInReceivedData) latestDateInReceivedData = sgv.timestamp
                        glucoseValues += sgv
                    }

                }
                activePlugin.activeNsClient?.updateLatestBgReceivedIfNewer(latestDateInReceivedData)
                // Was that sgv more less 5 mins ago ?
                if (T.msecs(dateUtil.now() - latestDateInReceivedData).mins() < 5L) {
                    rxBus.send(EventDismissNotification(Notification.NS_ALARM))
                    rxBus.send(EventDismissNotification(Notification.NS_URGENT_ALARM))
                }

                repository.runTransactionForResult(CgmSourceTransaction(glucoseValues, emptyList(), null, !nsClientSourcePlugin.isEnabled()))
                    .doOnError {
                        aapsLogger.error(LTag.DATABASE, "Error while saving values from NSClient App", it)
                        ret = Result.failure(workDataOf("Error" to it.toString()))
                    }
                    .blockingGet()
                    .also { result ->
                        result.updated.forEach {
                            xDripBroadcast.send(it)
                            nsClientSourcePlugin.detectSource(it)
                            aapsLogger.debug(LTag.DATABASE, "Updated bg $it")
                            processed++
                        }
                        result.inserted.forEach {
                            xDripBroadcast.send(it)
                            nsClientSourcePlugin.detectSource(it)
                            aapsLogger.debug(LTag.DATABASE, "Inserted bg $it")
                            processed++
                        }
                        ret = Result.success(workDataOf("latestDateInReceivedData" to latestDateInReceivedData))
                    }
            } catch (e: Exception) {
                aapsLogger.error("Unhandled exception", e)
                ret = Result.failure(workDataOf("Error" to e.toString()))
            }
            if (processed > 0)
                rxBus.send(EventNSClientNewLog("PROCESSED", "GlucoseValue $processed", if (sgvs is List<*>) NsClient.Version.V3 else NsClient.Version.V1))
            return ret
        }
    }
}