package ru.itmo.networkmonitor

import android.app.ActivityManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.net.NetworkCapabilities
import com.google.gson.Gson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

class NetworkMonitorTask(
    private val networkStatsManager: NetworkStatsManager,
    private val networkActivityManager: ActivityManager,
    private val packageList: List<String>
) {

    fun start() {
        Trd().start()
    }

    companion object {
        const val MILLIS_SPAN = 100
    }

    data class Trace(
        val className: String,
        val methodName: String,
        val lineNumber: Int,
        val trace: String
    )

    data class TraceElement(
        val className: String,
        val methodName: String,
        val lineNumber: Int,
    )

    inner class Trd : Thread() {
        override fun run() {
            while (true) {
                val curNanos = System.nanoTime()
                val client = HttpClient(CIO) {
                    expectSuccess = true
                    install(ContentNegotiation) {
                        json(Json {
                            ignoreUnknownKeys = true
                        })
                    }
                }
                val info = getInfo()
                if (info.isEmpty()) {
                    continue
                }
                runBlocking {
                    client.post("http://10.0.2.2:8080/addEvent") {
                        contentType(ContentType.Application.Json)
                        setBody(Gson().toJson(info))
                    }
                }
                val sleepValue = MILLIS_SPAN - (System.nanoTime() - curNanos)
                if (sleepValue > 0) {
                    sleep(sleepValue)
                }
            }
        }
    }

    data class Event(
        val fullMethodName: String,
        val methodName: String,
        val startTimestamp: Long,
        val finishTimestamp: Long,
        val bytesCount: Long,
        val stacktrace: String
    )

    data class Info(
        val bytes: Long,
        val startTs: Long,
        val endTs: Long,
        val state: Int,
        val id: Int,
        val networkType: Int,
        val tag: Int
    )

    fun getInfo(): List<Event> {
        val networkTypes = listOf(
            NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_CELLULAR
        )
        //val serv = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val informations = mutableListOf<Info>()
        var totalBytes = 0L
        val thread = Thread {
            for (networkType in networkTypes) {
                val now = System.currentTimeMillis()
                val start = now - MILLIS_SPAN * 1000 // 24 hours ago
                val end = now
                //val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                //context.startActivity(intent)

                val networkStats = networkStatsManager.querySummary(
                    networkType, null, start, end
                ) ?: continue
                val bucket = NetworkStats.Bucket()
                while (networkStats.hasNextBucket()) {
                    networkStats.getNextBucket(bucket)
                    totalBytes += bucket.rxBytes + bucket.txBytes
                    informations.add(
                        Info(
                            bucket.rxBytes + bucket.txBytes,
                            bucket.startTimeStamp,
                            bucket.endTimeStamp,
                            bucket.state,
                            bucket.uid,
                            networkType,
                            bucket.tag,
                        )
                    )
                }
            }
        }
        thread.start()
        thread.join()

        if (informations.isEmpty()) {
            return listOf()
        }
        val bucketsStartTime = informations[0].startTs
        val bucketsEndTime = informations[0].endTs
        val am = networkActivityManager
        val processes = am.runningAppProcesses
        val tasks = am.appTasks
        Thread.getAllStackTraces().keys.map { it.contextClassLoader }
        val traces = Thread.getAllStackTraces().keys.map {
            Pair(it.stackTrace.firstOrNull { st ->
                packageList.any { pkg ->
                    st.className.startsWith(
                        pkg
                    )
                }
            }, it.stackTrace)
        }
            .filter { it.first != null && it.first?.methodName != "getInfo" }
            .map {
                Trace(
                    it.first!!.className,
                    it.first!!.methodName,
                    it.first!!.lineNumber,
                    it.second.map { st ->
                        TraceElement(
                            st.className,
                            st.methodName,
                            st.lineNumber
                        )
                    }.joinToString("\n")
                )
            }
        return traces.map {
            Event(
                "${it.className}.${it.methodName}",
                it.methodName,
                bucketsStartTime,
                bucketsEndTime,
                totalBytes,
                it.trace
            )
        }
    }
}
