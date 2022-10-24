package info.nightscout.sdk.interfaces

import info.nightscout.sdk.localmodel.Status
import info.nightscout.sdk.localmodel.entry.NSSgvV3
import info.nightscout.sdk.localmodel.treatment.NSTreatment
import info.nightscout.sdk.remotemodel.LastModified

interface NSAndroidClient {

    suspend fun getVersion(): String
    suspend fun getStatus(): Status
    suspend fun getEntries(): String

    suspend fun getLastModified(): LastModified
    suspend fun getSgvs(): List<NSSgvV3>
    suspend fun getSgvsModifiedSince(from: Long): List<NSSgvV3>
    suspend fun getSgvsNewerThan(from: Long, limit: Long): List<NSSgvV3>
    suspend fun getTreatmentsModifiedSince(from: Long, limit: Long): List<NSTreatment>
}