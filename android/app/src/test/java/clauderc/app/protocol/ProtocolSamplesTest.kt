package clauderc.app.protocol

import java.io.File
import java.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Every sample in internal/protocol/testdata must decode into the Kotlin model
 * and encode back to the same fields (task 11.3). A JSON null and a missing
 * key count as the same field.
 */
class ProtocolSamplesTest {

    private val bodies: Map<String, KSerializer<*>> = mapOf(
        "machines.list.req" to Empty.serializer(),
        "machines.list.res" to MachinesListResponse.serializer(),
        "machine.status.evt" to Machine.serializer(),
        "client.gone.evt" to Empty.serializer(),
        "sessions.subscribe.req" to Empty.serializer(),
        "sessions.subscribe.res" to SessionList.serializer(),
        "sessions.unsubscribe.req" to Empty.serializer(),
        "sessions.unsubscribe.res" to Empty.serializer(),
        "sessions.changed.evt" to SessionList.serializer(),
        "chat.open.req" to ChatOpenRequest.serializer(),
        "chat.open.res" to ChatOpenResponse.serializer(),
        "chat.close.req" to ChatCloseRequest.serializer(),
        "chat.close.res" to Empty.serializer(),
        "chat.send.req" to ChatSendRequest.serializer(),
        "chat.send.res" to ChatSendResponse.serializer(),
        "chat.answer.req" to ChatAnswerRequest.serializer(),
        "chat.answer.res" to Empty.serializer(),
        "chat.interrupt.req" to SessionIdRequest.serializer(),
        "chat.interrupt.res" to Empty.serializer(),
        "chat.models.req" to SessionIdRequest.serializer(),
        "chat.models.res" to ChatModelsResponse.serializer(),
        "chat.setModel.req" to ChatSetModelRequest.serializer(),
        "chat.setModel.res" to Empty.serializer(),
        "chat.setPermissionMode.req" to ChatSetPermissionModeRequest.serializer(),
        "chat.setPermissionMode.res" to Empty.serializer(),
        "chat.commands.req" to SessionIdRequest.serializer(),
        "chat.commands.res" to ChatCommandsResponse.serializer(),
        "chat.toolOutput.req" to ChatToolOutputRequest.serializer(),
        "chat.toolOutput.res" to ToolResult.serializer(),
        "chat.image.req" to ChatImageRequest.serializer(),
        "chat.image.res" to ChatImageResponse.serializer(),
        "chat.items.evt" to ChatItemsEvent.serializer(),
        "chat.pending.evt" to ChatPendingEvent.serializer(),
        "chat.state.evt" to ChatStateEvent.serializer(),
        "session.create.req" to SessionCreateRequest.serializer(),
        "session.create.res" to SessionCreateResponse.serializer(),
        "session.stop.req" to SessionIdRequest.serializer(),
        "session.stop.res" to Empty.serializer(),
        "session.remove.req" to SessionIdRequest.serializer(),
        "session.remove.res" to Empty.serializer(),
        "dirs.recent.req" to Empty.serializer(),
        "dirs.recent.res" to DirsRecentResponse.serializer(),
        "usage.get.req" to Empty.serializer(),
        "usage.get.res" to UsageResponse.serializer(),
        "git.status.req" to DirRequest.serializer(),
        "git.status.res" to GitStatusResponse.serializer(),
        "git.log.req" to GitLogRequest.serializer(),
        "git.log.res" to GitLogResponse.serializer(),
        "git.show.req" to GitShowRequest.serializer(),
        "git.show.res" to GitShowResponse.serializer(),
        "git.diff.req" to GitDiffRequest.serializer(),
        "git.diff.res" to GitDiffResponse.serializer(),
        "git.branches.req" to DirRequest.serializer(),
        "git.branches.res" to GitBranchesResponse.serializer(),
        "git.stage.req" to GitPathRequest.serializer(),
        "git.stage.res" to Empty.serializer(),
        "git.unstage.req" to GitPathRequest.serializer(),
        "git.unstage.res" to Empty.serializer(),
        "git.commit.req" to GitCommitRequest.serializer(),
        "git.commit.res" to GitCommitResponse.serializer(),
        "git.pull.req" to DirRequest.serializer(),
        "git.pull.res" to Empty.serializer(),
        "git.push.req" to GitPushRequest.serializer(),
        "git.push.res" to Empty.serializer(),
        "git.switch.req" to GitSwitchRequest.serializer(),
        "git.switch.res" to Empty.serializer(),
        "dir.busy.req" to DirRequest.serializer(),
        "dir.busy.res" to DirBusyResponse.serializer(),
        "term.open.req" to SessionIdRequest.serializer(),
        "term.open.res" to TermScreen.serializer(),
        "term.key.req" to TermKeyRequest.serializer(),
        "term.key.res" to Empty.serializer(),
        "term.close.req" to SessionIdRequest.serializer(),
        "term.close.res" to Empty.serializer(),
        "term.screen.evt" to TermScreen.serializer(),
    )

    private val testdata: File by lazy {
        val url = javaClass.classLoader!!.getResource("machines.list.req.json")
            ?: error("internal/protocol/testdata is not on the test classpath")
        requireNotNull(File(url.toURI()).parentFile)
    }

    @Test fun everyEnvelopeSampleRoundTrips() {
        val files = testdata.listFiles { f -> f.isFile && f.name.endsWith(".json") }!!.sortedBy { it.name }
        assertTrue("expected protocol samples, found ${files.size}", files.size > 50)
        val failures = mutableListOf<String>()
        for (file in files) {
            runCatching { roundTrip(file) }.onFailure { failures += "${file.name}: ${it.message}" }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    private fun roundTrip(file: File) {
        val raw = file.readText()
        val env = Envelope.decode(raw)
        val key = "${env.method}.${env.kind}"
        check(file.name.startsWith("$key.")) { "file name does not start with $key." }
        val serializer = bodies[key] ?: error("no Kotlin body type for $key")
        val reencodedBody = if (env.error == null && env.body != null) reencode(serializer, env.body) else env.body
        val out = env.copy(body = reencodedBody).encode()
        assertSameFields(ProtocolJson.parseToJsonElement(raw), ProtocolJson.parseToJsonElement(out))
    }

    @Test fun phoneHttpSamplesRoundTrip() {
        val dir = File(testdata, "http")
        val files = dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        var checked = 0
        for (file in files) {
            val name = file.name
            val status = Regex("""\.res\.(\d{3})""").find(name)?.groupValues?.get(1)?.toInt()
            val serializer: KSerializer<*> = when {
                status != null && status >= 400 -> ErrorBody.serializer()
                name.startsWith("pair.req") -> PairRequest.serializer()
                name.startsWith("pair.res.200") -> PairResponse.serializer()
                else -> continue // admin endpoints are used by the agent only
            }
            val raw = ProtocolJson.parseToJsonElement(file.readText())
            assertSameFields(raw, reencode(serializer, raw), name)
            checked++
        }
        assertTrue("pair and error samples should be present", checked >= 6)
    }

    @Test fun timesWithOffsetOrZParseToTheSameInstant() {
        assertEquals(Instant.parse("2026-11-13T06:42:15Z"), parseInstant("2026-11-12T23:42:15-07:00"))
        assertEquals(Instant.parse("2026-11-13T06:42:15.906015Z"), parseInstant("2026-11-12T23:42:15.906015-07:00"))
        assertEquals(Instant.parse("2026-11-12T09:30:00Z"), parseInstant("2026-11-12T09:30:00Z"))
    }

    @Test fun rejectsOtherVersions() {
        val result = runCatching { Envelope.decode("""{"v":2,"kind":"req"}""") }
        assertTrue(result.isFailure)
    }

    @Suppress("UNCHECKED_CAST")
    private fun reencode(serializer: KSerializer<*>, body: JsonElement): JsonElement {
        val s = serializer as KSerializer<Any>
        return ProtocolJson.encodeToJsonElement(s, ProtocolJson.decodeFromJsonElement(s, body))
    }

    private fun assertSameFields(expected: JsonElement, actual: JsonElement, label: String = "") {
        assertEquals(label, dropNulls(expected), dropNulls(actual))
    }

    /** Also writes whole numbers one way, since Kotlin encodes the Double 30 as 30.0. */
    private fun dropNulls(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.filterValues { it !is JsonNull }.mapValues { dropNulls(it.value) })
        is JsonArray -> JsonArray(e.map(::dropNulls))
        is JsonPrimitive -> if (!e.isString && e.doubleOrNull?.let { it % 1.0 == 0.0 } == true) JsonPrimitive(e.double.toLong()) else e
        else -> e
    }
}
