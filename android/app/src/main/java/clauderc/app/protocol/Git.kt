package clauderc.app.protocol

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mirrors internal/protocol/git.go. */
object GitLimits {
    const val LOG_PAGE_SIZE = 100
    const val DIFF_COLLAPSE_LINES = 1000
}

@Serializable
enum class FileChange {
    @SerialName("added") Added,
    @SerialName("modified") Modified,
    @SerialName("deleted") Deleted,
    @SerialName("renamed") Renamed,
    @SerialName("untracked") Untracked,
}

@Serializable
data class FileStatus(
    val path: String,
    val oldPath: String? = null,
    val change: FileChange = FileChange.Modified,
)

@Serializable
data class DirRequest(val dir: String)

@Serializable
data class GitStatusResponse(
    /** Empty on a detached HEAD. */
    val branch: String = "",
    val upstream: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
    val staged: List<FileStatus> = emptyList(),
    val unstaged: List<FileStatus> = emptyList(),
)

@Serializable
data class GitLogRequest(val dir: String, val page: Int = 0)

@Serializable
enum class RefKind {
    @SerialName("branch") Branch,
    @SerialName("remoteBranch") RemoteBranch,
    @SerialName("tag") Tag,
}

@Serializable
data class Ref(val name: String, val kind: RefKind = RefKind.Branch)

/** A line from lane [from] on this commit's row to lane [to] on the next row down. */
@Serializable
data class Edge(val from: Int, val to: Int)

@Serializable
data class Commit(
    val hash: String,
    val parents: List<String> = emptyList(),
    val author: String = "",
    @Serializable(with = InstantSerializer::class) val time: Instant,
    val subject: String = "",
    val refs: List<Ref>? = null,
    val lane: Int = 0,
    val edges: List<Edge> = emptyList(),
)

@Serializable
data class GitLogResponse(val commits: List<Commit> = emptyList(), val hasMore: Boolean = false)

@Serializable
data class GitShowRequest(val dir: String, val hash: String)

@Serializable
data class CommitFile(
    val path: String,
    val oldPath: String? = null,
    val added: Int = 0,
    val deleted: Int = 0,
    val binary: Boolean = false,
)

@Serializable
data class GitShowResponse(
    val hash: String,
    val parents: List<String> = emptyList(),
    val author: String = "",
    @Serializable(with = InstantSerializer::class) val time: Instant,
    val message: String = "",
    val files: List<CommitFile> = emptyList(),
)

@Serializable
data class GitDiffRequest(
    val dir: String,
    val path: String,
    val hash: String? = null,
    val staged: Boolean = false,
)

@Serializable
data class GitDiffResponse(
    val path: String,
    val binary: Boolean = false,
    val patch: String? = null,
    val collapsed: Boolean = false,
)

@Serializable
data class RemoteBranch(val remote: String, val name: String)

@Serializable
data class GitBranchesResponse(
    val current: String = "",
    val local: List<String> = emptyList(),
    val remote: List<RemoteBranch> = emptyList(),
)

@Serializable
data class GitPathRequest(val dir: String, val path: String)

@Serializable
data class GitCommitRequest(val dir: String, val message: String)

@Serializable
data class GitCommitResponse(val hash: String)

@Serializable
data class GitPushRequest(val dir: String, val setUpstream: Boolean = false)

@Serializable
data class GitSwitchRequest(val dir: String, val branch: String)

@Serializable
data class DirBusyResponse(val busy: Boolean = false)
