package guardianlink.sidecar

import guardianlink.gates.Action
import guardianlink.gates.Condition
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON encoding of the closed Action AST (see gates/Action.kt).
 *
 *   {"type":"read","recordId":"profile","fields":["name"]}
 *   {"type":"write","recordId":"profile","fields":{"name":"Neo"}}
 *   {"type":"delete","recordId":"profile"}
 *   {"type":"sequence","steps":[...]}
 *   {"type":"guarded","condition":{...},"then":{...},"otherwise":{...}}
 *
 *   conditions:
 *   {"type":"fieldEquals","recordId":"r","field":"f","expected":"v"}
 *   {"type":"and","parts":[...]}
 *   {"type":"not","inner":{...}}
 *
 * This layer checks SHAPE only (present fields, correct JSON types,
 * known type tags). Membership in A_total — node bounds, depth bounds,
 * byte bounds — is enforced by the engine at Gate 0 (validateAction),
 * never duplicated here. Anything malformed is a [BadActionException]
 * and becomes HTTP 400; it never reaches the engine.
 */
class BadActionException(message: String) : Exception(message)

private fun JSONObject.reqString(name: String): String {
    if (!has(name) || isNull(name)) throw BadActionException("missing field '$name'")
    val v = opt(name)
    if (v !is String) throw BadActionException("field '$name' must be a string")
    return v
}

private fun JSONObject.reqObject(name: String): JSONObject {
    if (!has(name) || isNull(name)) throw BadActionException("missing field '$name'")
    val v = opt(name)
    if (v !is JSONObject) throw BadActionException("field '$name' must be an object")
    return v
}

private fun JSONObject.reqArray(name: String): JSONArray {
    if (!has(name) || isNull(name)) throw BadActionException("missing field '$name'")
    val v = opt(name)
    if (v !is JSONArray) throw BadActionException("field '$name' must be an array")
    return v
}

private fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).map { i ->
        val v = opt(i)
        if (v !is JSONObject) throw BadActionException("array element $i must be an object")
        v
    }

private fun JSONArray.strings(): List<String> =
    (0 until length()).map { i ->
        val v = opt(i)
        if (v !is String) throw BadActionException("array element $i must be a string")
        v
    }

fun parseCondition(json: JSONObject): Condition {
    return when (val t = json.optString("type", null)) {
        "fieldEquals" -> Condition.FieldEquals(
            json.reqString("recordId"),
            json.reqString("field"),
            json.reqString("expected"),
        )
        "and" -> {
            val parts = json.reqArray("parts").objects()
            if (parts.isEmpty()) throw BadActionException("'and' needs at least one part")
            Condition.And(parts.map(::parseCondition))
        }
        "not" -> Condition.Not(parseCondition(json.reqObject("inner")))
        else -> throw BadActionException("unknown condition type '$t'")
    }
}

fun parseAction(json: JSONObject): Action {
    return when (val t = json.optString("type", null)) {
        "read" -> Action.Read(
            json.reqString("recordId"),
            json.reqArray("fields").strings(),
        )
        "write" -> {
            val fieldsObj = json.reqObject("fields")
            val fields = mutableMapOf<String, String>()
            for (k in fieldsObj.keys()) {
                val v = fieldsObj.opt(k)
                if (v !is String) throw BadActionException("write field '$k' must be a string")
                fields[k] = v
            }
            Action.Write(json.reqString("recordId"), fields)
        }
        "delete" -> Action.Delete(json.reqString("recordId"))
        "sequence" -> {
            val steps = json.reqArray("steps").objects()
            if (steps.isEmpty()) throw BadActionException("'sequence' needs at least one step")
            Action.Sequence(steps.map(::parseAction))
        }
        "guarded" -> Action.Guarded(
            parseCondition(json.reqObject("condition")),
            parseAction(json.reqObject("then")),
            json.optJSONObject("otherwise")?.let(::parseAction),
        )
        else -> throw BadActionException("unknown action type '$t'")
    }
}
