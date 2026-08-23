package guardianlink.adapters

data class CanonicalRecord(
    val domainId: String,
    val recordId: String,
    val timestampEpochMs: Long,
    val fields: Map<String, String>,
    val sensitivityTier: SensitivityTier
)

enum class SensitivityTier { PUBLIC, PERSONAL, SENSITIVE, CRITICAL }

interface DomainAdapter<RawRecord> {
    val domainId: String
    fun normalize(raw: RawRecord): CanonicalRecord
    fun validate(raw: RawRecord): Boolean
}

class ImmutableGraph private constructor(
    val nodes: Map<String, CanonicalRecord>,
    val edges: Set<Pair<String, String>>
) {
    companion object {
        fun empty(): ImmutableGraph = ImmutableGraph(emptyMap(), emptySet())
    }

    fun withNode(record: CanonicalRecord): ImmutableGraph =
        ImmutableGraph(nodes + (record.recordId to record), edges)

    fun withEdge(fromId: String, toId: String): ImmutableGraph {
        require(nodes.containsKey(fromId) && nodes.containsKey(toId)) {
            "both nodes must exist before an edge can connect them"
        }
        return ImmutableGraph(nodes, edges + (fromId to toId))
    }
}