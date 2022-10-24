package info.nightscout.androidaps.plugins.sync.nsclientV3.extensions

import info.nightscout.androidaps.database.embedments.InterfaceIDs
import info.nightscout.androidaps.database.entities.Bolus
import info.nightscout.sdk.localmodel.treatment.NSBolus

fun NSBolus.toBolus(): Bolus =
    Bolus(
        isValid = isValid,
        timestamp = date,
        utcOffset = utcOffset,
        amount = insulin,
        type = type.toBolusType(),
        notes = notes,
        interfaceIDs_backing = InterfaceIDs(nightscoutId = identifier, pumpId = pumpId, pumpType = InterfaceIDs.PumpType.fromString(pumpType), pumpSerial = pumpSerial)
    )

fun NSBolus.BolusType?.toBolusType(): Bolus.Type =
    when (this) {
        NSBolus.BolusType.NORMAL  -> Bolus.Type.NORMAL
        NSBolus.BolusType.SMB     -> Bolus.Type.SMB
        NSBolus.BolusType.PRIMING -> Bolus.Type.PRIMING
        null                      -> Bolus.Type.NORMAL
    }