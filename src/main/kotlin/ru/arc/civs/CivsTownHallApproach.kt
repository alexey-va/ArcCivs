package ru.arc.civs

import java.util.UUID
import kotlin.math.abs
import kotlin.math.sign

/** A founding capture and local fragments of real outbound routes. Complete routes remain in their original journals. */
data class CivHallBranch(val owner: UUID, val plan: UUID, val routeSha256: String, val prefix: List<CivPosition>)
data class CivHallApproach(val founding: CivPerimeterCapture, val branches: List<CivHallBranch>,
    val crossings: List<CivHallBranch>? = null) {
    internal fun detached() = copy(branches = branches.map { it.copy(prefix = it.prefix.toList()) },
        crossings = crossings?.map { it.copy(prefix = it.prefix.toList()) })
}

/** The same source-derived columns are used to quote, validate, navigate and recover a paid Hall repair. */
internal class CivsTownHallApproach private constructor(
    val hall: CivBuilding, val witness: CivHallApproach, private val axis: Int, private val rows: List<String>,
    private val feet: List<Int>, private val style: String
) {
    private val front = CivsAutonomousGeometry.frontage(hall)
    private fun lane(p: CivPosition) = (p.x - hall.entrance.x) * front.z - (p.z - hall.entrance.z) * front.x
    private fun depth(p: CivPosition) = (p.x - hall.entrance.x) * front.x + (p.z - hall.entrance.z) * front.z
    private fun point(s: Int, y: Int, d: Int) = CivPosition(hall.entrance.x + s * front.z + d * front.x,
        y, hall.entrance.z - s * front.x + d * front.z)
    private fun stair(d: Int) = d > 0 && feet[d] != feet[d - 1]
    private fun rowY(d: Int) = if (d < 1) feet.first() - 1 else maxOf(feet[d], feet[d - 1]) - 1
    private val inward = when { front.z == 1 -> "north"; front.z == -1 -> "south"; front.x == 1 -> "west"; else -> "east" }
    private val outward = when (inward) { "north" -> "south"; "south" -> "north"; "east" -> "west"; else -> "east" }

    /** Only the old shoulder of a transverse street beyond the last flat row can replace that
     * row's Hall ownership. Its immutable route must independently specify the same flat paving. */
    private fun bordersTerminal(street: List<CivPosition>) = street.all { depth(it) > feet.lastIndex } &&
        street.any { depth(it) == feet.lastIndex + 1 && abs(lane(it) - axis) <= 2 }

    private fun terminalShoulder(street: List<CivPosition>): Set<CivPosition> {
        val end = feet.lastIndex
        if (feet[end] != feet[end-1]) return emptySet()
        val near = street.filter { depth(it) in end+1..end+2 }
        val direction = (lane(near.last()) - lane(near.first())).sign
        if (direction == 0 || (axis-1..axis+1).any { n -> near.none { lane(it) == n } } ||
            near.zipWithNext().any { (a,b) -> abs(a.x-b.x) + abs(a.z-b.z) != 1 ||
                (lane(b)-lane(a))*direction < 0 }) return emptySet()
        val profile = CivsAutonomousTerraform.roadProfile(street).mapValues { it.value.y }
        val shared = mutableSetOf<CivPosition>()
        for (side in axis-1..axis+1) {
            val p = point(side,feet[end]-1,end)
            val old = profile[civsTerrainKey(p.x,p.z)] ?: continue
            if (old != p.y || CivsSettlementLayout.pathPavement(p,profile,style) != "minecraft:dirt_path") return emptySet()
            shared += p
        }
        return shared
    }

    private val outerStreets = witness.crossings.orEmpty().filter { bordersTerminal(it.prefix) }
        .associate { it.owner to terminalShoulder(it.prefix) }
    val retainedTerminalFloors = outerStreets.values.flatten().toSet()

    private val terminalStreet=buildMap<Int,CivPosition> {
        for (branch in witness.branches) for ((a,b) in branch.prefix.zipWithNext()) {
            if (depth(a)!=feet.lastIndex || depth(b)!=feet.lastIndex || abs(lane(a)-axis)!=1 ||
                lane(b)!=axis || a.y!=b.y+1 || b.y!=feet.last()) continue
            put(lane(a),a)
        }
        for (branch in witness.branches) for ((a,b) in branch.prefix.zipWithNext()) {
            if (depth(a)!=feet.lastIndex || depth(b)!=feet.lastIndex || lane(a)!=axis ||
                abs(lane(b)-axis)!=1 || b.y!=a.y+1 || a.y!=feet.last()) continue
            put(lane(b),b)
        }
    }
    private val path: Map<CivPosition, String> = buildMap {
        for (d in -rows.size..feet.lastIndex) for (s in axis - 1..axis + 1) {
            val junction=terminalStreet[s]?.takeIf { d==feet.lastIndex }
            if (junction!=null) continue // The side street already owns its terminal tread and signed height.
            val p = point(s, rowY(d), d)
            if (p in retainedTerminalFloors) continue
            if (d < 0 && hall.control?.let { it.x == p.x && it.z == p.z } == true) continue
            put(p, when {
                d < 0 -> rows[-d - 1]
                stair(d) -> {
                    val facing = if (feet[d] < feet[d - 1]) inward else outward
                    "minecraft:${CivsSettlementLayout.wood(style)}_stairs[facing=$facing,half=bottom,shape=straight,waterlogged=false]"
                }
                else -> "minecraft:dirt_path"
            })
        }
        for (d in 1..feet.lastIndex) if (stair(d)) for (s in listOf(axis - 2, axis + 2))
            put(point(s, rowY(d), d), CivsEntrancePath.GRASS)
    }
    private val paid = witness.branches + witness.crossings.orEmpty()
    private val paidProfiles = paid.associate { it.owner to CivsAutonomousTerraform.roadProfile(it.prefix) }
    private val paidHeights = paidProfiles.mapValues { (_, profile) -> profile.mapValues { it.value.y } }
    private val mergedHeights = buildMap {
        paid.forEach { branch -> branch.prefix.forEach { putIfAbsent(civsTerrainKey(it.x, it.z), it.y - 1) } }
        paidHeights.values.forEach { profile -> profile.forEach { (k, y) -> putIfAbsent(k, y) } }
    }

    private val links = linkedMapOf<CivPosition,String>()
    private fun MutableMap<CivPosition,String>.finishAt(s: Int, d: Int, y: Int, highSide: Int? = null, highDepth: Int = d) {
        val p = point(s,y,d)
        if (p in path || p in retainedTerminalFloors) return
        val finish = if (highSide == null) "minecraft:dirt_path" else {
            val high = point(highSide,y,highDepth)
            val facing = when {
                high.x > p.x -> "east"
                high.x < p.x -> "west"
                high.z > p.z -> "south"
                else -> "north"
            }
            "minecraft:${CivsSettlementLayout.wood(style)}_stairs[facing=$facing,half=bottom,shape=straight,waterlogged=false]"
        }
        putIfAbsent(p,finish)
    }
    private fun downSpine(d: Int) = buildList {
        add(hall.entrance)
        if (axis != 0) add(point(axis,feet.first(),0))
        for (i in 1..d) add(point(axis,if (i<feet.size) rowY(i)+1 else feet.last(),i))
    }
    private fun planJoin(branch: CivHallBranch, pending: MutableMap<CivPosition,String>): List<CivPosition>? {
        val route = branch.prefix
        val bottom = feet.lastIndex
        val end = route.firstOrNull { depth(it) in bottom..bottom+1 && abs(lane(it)-axis)<=4 && it.y==feet.last() }
        if (end != null) {
            val d = depth(end)
            val side = lane(end)-axis
            val walk=downSpine(d).toMutableList()
            for (i in 1..abs(side)) {
                val s=axis+i*side.sign
                if (i>=2) pending.finishAt(s,d,feet.last()-1)
                walk.add(point(s,feet.last(),d))
            }
            return walk
        }
        // Leave at a genuine, level side street even if its early paid turn lies alongside our flight.
        val sideStreet = route.indices.firstOrNull { i ->
            i>0 && depth(route[i]) in 1 until bottom && abs(lane(route[i])-axis)>=2 &&
                route[i].y==feet[depth(route[i])] && !stair(depth(route[i])) &&
                route.getOrNull(i+1)?.let { depth(it)==depth(route[i]) && abs(lane(it)-axis)>abs(lane(route[i])-axis) }==true
        }
        if (sideStreet!=null) {
            val at=route[sideStreet]
            val d=depth(at)
            val side=lane(at)-axis
            val walk=downSpine(d).toMutableList()
            for (i in 1..abs(side)) {
                val s=axis+i*side.sign
                if (i>=2) pending.finishAt(s,d,at.y-1)
                walk.add(point(s,at.y,d))
            }
            return walk
        }
        val crest = route.takeWhile { depth(it)==0 }
        val outside = crest.indexOfFirst { abs(lane(it)-axis)>=2 }
        if (outside<1 || outside>=crest.lastIndex) return null
        val outer=crest[outside]
        val s=lane(outer)
        val direction=(s-axis).sign
        val previousY=feet.first()
        val next=crest[outside+1]
        val immediate = crest.take(outside).all { it.y == previousY } && crest[outside-1].y == previousY &&
            outer.y<previousY && next.y<outer.y
        val insideGrade = crest.getOrNull(outside-1)?.let { p -> p.y == previousY && abs(lane(p)-axis)<=1 } == true
        // With no room left on a continuously descending ridge, use the already supported side of
        // our flight at the same stratum. Two half-steps there meet the original transverse stair.
        val descent=insideGrade && immediate && previousY-outer.y==1 && next.y==outer.y-1 &&
            !crest.any { p -> abs(lane(p)-axis)==2 && p.y==previousY }
        if (descent) {
            val d=(2 until bottom).firstOrNull { feet[it]==next.y && stair(it) } ?: return null
            if (d!=2) return null
            val shoulder=point(s,rowY(d),d)
            if (path[shoulder]!=CivsEntrancePath.GRASS) return null
            val farther=s+direction
            val walk=downSpine(d).toMutableList()
            walk+=point(s-direction,rowY(d)+1,d)
            walk+=shoulder.copy(y=shoulder.y+1)
            pending.finishAt(farther,d,rowY(d),farther,d+1)
            walk+=point(farther,rowY(d)+1,d)
            // The old paid side tread is entered longitudinally. It needs a half-step from the 
            // level between the Hall and the lower side street, facing back toward that level.
            pending.finishAt(farther,d-1,rowY(d)-1,farther,d)
            walk+=point(farther,rowY(d),d-1)
            walk+=next
            return walk
        }
        // A side street one level below the landing can leave along the first tread. Its
        // transverse stair sits beyond the earth cheek and rejoins the paid level street.
        val streetEnd = crest.getOrNull(outside + 2)
        if (feet[1] == previousY - 1 && outer.y == feet[1] && next.y == outer.y &&
            streetEnd?.y == outer.y && lane(next) == s + direction && lane(streetEnd) == s + 2 * direction &&
            path[point(s, rowY(1), 1)] == CivsEntrancePath.GRASS) {
            val walk = downSpine(1).toMutableList()
            for (i in 1..3) walk += point(axis + direction * i, previousY, 1)
            pending.finishAt(lane(next), 1, previousY - 1, s)
            walk += point(lane(streetEnd), streetEnd.y, 1)
            walk += streetEnd
            return walk
        }
        if (crest.take(outside).all { it.y==previousY } && outer.y==previousY &&
            next.y<outer.y) {
            for ((a,b) in crest.zipWithNext()) {
                if (abs(lane(a)-axis)>=2 && a.y==b.y+1) pending.finishAt(lane(a),0,a.y-1,lane(a)-direction)
            }
            return crest
        }
        val walk=mutableListOf(hall.entrance)
        var previous=hall.entrance
        for (p in crest.drop(1)) {
            val l=lane(p)
            if (abs(l-axis)<=1) {
                previous=point(l,feet.first(),0)
                walk+=previous
                continue
            }
            val farther=crest.getOrNull(crest.indexOf(p)+1)
            val upper=maxOf(previous.y,p.y)
            val higherSide=if (p.y<previous.y) l-direction else l+direction
            if (p.y != previous.y) pending.finishAt(l,0,upper-1,higherSide)
            else if (farther!=null && farther.y!=p.y) pending.finishAt(l,0,p.y-1,l-direction)
            walk+=point(l,upper,0)
            previous=p
        }
        // A dropped side exit can use the first unchanged flat grade outside the core.
        return walk
    }

    private val joins=buildMap<UUID,List<CivPosition>> {
        for (branch in witness.branches) {
            val pending=linkedMapOf<CivPosition,String>()
            val walk=planJoin(branch,pending) ?: continue
            if (walk.last() !in branch.prefix || !publishable(walk)) continue
            put(branch.owner,walk)
            for ((p,finish) in pending) links.putIfAbsent(p,finish)
        }
    }

    private val retiredShoulders = hashMapOf<CivPosition,String>()

    /** New treads, exact connections to each paid street and the redundant pavement left behind. */
    val surfaces: Map<CivPosition, String> = buildMap {
        putAll(path)
        for ((p,finish) in links) putIfAbsent(p,finish)
        for (d in 0 until feet.lastIndex) for (s in axis - 3..axis + 3) {
            if (abs(s - axis) <= 1) continue
            val p = point(s, rowY(d), d)
            val nearby = witness.branches.filter { branch -> branch.prefix.any { old ->
                abs(old.x.toLong() - p.x) <= 1L && abs(old.z.toLong() - p.z) <= 1L } }
            // A separate crossing or an unconnected side exit still owns this column.
            if (nearby.isEmpty() || nearby.any { it.owner !in joins }) continue
            if (witness.crossings.orEmpty().any { c -> c.prefix.any { it.x==p.x && it.z==p.z } }) continue
            if (joins.values.any { walk -> walk.any { it.x==p.x && it.z==p.z } }) continue
            val outerShoulder = d > 0 && abs(s - axis) == 3 &&
                nearby.none { owner -> owner.prefix.any { old -> lane(old) == s && depth(old) == d } }
            if (outerShoulder) {
                val innerLane = s - (s - axis).sign
                val parallelSlope = stair(d) && nearby.any { owner -> owner.prefix.zipWithNext().any { (a,b) ->
                    lane(a) == innerLane && lane(b) == innerLane && depth(a) == d && depth(b) == d + 1 &&
                        a.y != b.y
                } }
                if (!parallelSlope) continue
            }
            val oldTurn = d==0 && nearby.any { owner -> owner.prefix.windowed(3).any { (a,b,c) ->
                b.x==p.x && b.z==p.z && a.x!=c.x && a.z!=c.z
            } } && nearby.none { owner -> joins[owner.owner].orEmpty().any { it.x==p.x && it.z==p.z } }
            if (keys.none { it.x==p.x && it.z==p.z } && (oldTurn || d>0 && nearby.any { b -> b.prefix.zipWithNext().any { (a, b) ->
                    lane(a) == lane(b) && abs(lane(a) - s) <= 1 && depth(a) == d && abs(lane(a) - axis) >= 2 } })) {
                val old = nearby.firstNotNullOfOrNull { paidProfiles[it.owner]?.get(civsTerrainKey(p.x, p.z)) } ?: continue
                // Ground at the edge follows the outer walking height; no extra elevated tier beyond the cheek.
                if (outerShoulder) {
                    val key = civsTerrainKey(p.x,p.z)
                    val higher = if (feet[d] < feet[d-1]) inward else outward
                    val tread = "minecraft:${CivsSettlementLayout.wood(style)}_stairs[facing=$higher,half=bottom,shape=straight,waterlogged=false]"
                    if (nearby.any { paidProfiles[it.owner]?.get(key) != old } || !paidFinish(old,tread)) continue
                    retiredShoulders[old] = tread
                }
                put(if (d>0 && abs(s-axis)==2) p else old,CivsEntrancePath.GRASS)
            }
        }
    }
    val columns = surfaces.keys.associateBy { civsTerrainKey(it.x, it.z) }
    fun retiredShoulder(p: CivPosition) = p in retiredShoulders
    fun bank(p: CivPosition) = surfaces[p] == CivsEntrancePath.GRASS
    fun yard(p: CivPosition) = depth(p) < 0 && p in surfaces
    /** Historical pavement belongs to its actual paid grade, style and downhill direction. */
    fun paidFinish(p: CivPosition, data: String): Boolean {
        if (!CivsRoadMaintenance.surface(data)) return false
        val key = civsTerrainKey(p.x, p.z)
        fun matches(expected: String): Boolean {
            if (data==expected) return true
            if ("shape=straight" !in expected || "shape=outer_" !in data) return false
            val forward=when (expected.substringAfter("facing=").substringBefore(',')) {
                "north" -> 0 to -1; "south" -> 0 to 1; "east" -> 1 to 0; else -> -1 to 0
            }
            val right=(-forward.second) to forward.first
            val side=if (data==expected.replace("shape=straight","shape=outer_right")) right
                else if (data==expected.replace("shape=straight","shape=outer_left")) -right.first to -right.second else return false
            val neighbour=p.copy(x=p.x+forward.first,z=p.z+forward.second)
            val facing=when (side) { 1 to 0 -> "east"; -1 to 0 -> "west"; 0 to 1 -> "south"; else -> "north" }
            val neighbourStair=expected.replaceAfter("facing=","$facing,half=bottom,shape=straight,waterlogged=false]")
            return paid.any { branch ->
                val heights=paidHeights.getValue(branch.owner)
                heights[civsTerrainKey(neighbour.x,neighbour.z)]==p.y &&
                    (CivsSettlementLayout.pavement(neighbour,heights)==neighbourStair ||
                        CivsSettlementLayout.pathPavement(neighbour,heights,style,legacyStairAtTurn=true)==neighbourStair)
            }
        }
        return paid.any { branch ->
            val levels = paidHeights.getValue(branch.owner)
            if (levels[key] != p.y) return@any false
            val turn = p in CivsSettlementLayout.turnFloors(branch.prefix)
            val downhill = CivsSettlementLayout.routeDownhill(p, branch.prefix)
            listOf(levels, mergedHeights).any { heights ->
                heights[key] == p.y && (matches(CivsSettlementLayout.pavement(p, heights)) ||
                    matches(CivsSettlementLayout.pathPavement(p, heights, style, legacyStairAtTurn = true)) ||
                    matches(CivsSettlementLayout.pathPavement(p, heights, style, turn,
                        downhill?.takeIf { heights[civsTerrainKey(it.x, it.z)] == it.y })))
            }
        }
    }

    /** During construction the other finished lane of the same three-wide strip can carry an old
     * local edge. All four footprints, contacts and body clearance are subsequently proved in-world. */
    fun adjacentWalks(edge: List<CivPosition>): List<List<CivPosition>> {
        if (edge.size!=2) return emptyList()
        val (a,b)=edge
        val dx=b.x-a.x
        val dz=b.z-a.z
        return listOf(-1,1).mapNotNull { side ->
            val ka=civsTerrainKey(a.x+dz*side,a.z-dx*side)
            val kb=civsTerrainKey(b.x+dz*side,b.z-dx*side)
            val fa=columns[ka] ?: return@mapNotNull null
            val fb=columns[kb] ?: return@mapNotNull null
            if (bank(fa) || bank(fb)) return@mapNotNull null
            listOf(a,fa.copy(y=fa.y+1),fb.copy(y=fb.y+1),b)
        }
    }

    /** Every physically traversable old edge affected by this column must survive, or have a usable replacement. */
    data class CrossingCheck(val before: List<CivPosition>, val after: List<CivPosition>)
    fun crossingChecks(p: CivPosition, changes: List<CivPlanBlock>): List<CrossingCheck> {
        if (changes.all { it.position.y>p.y && cover(it.before) }) return emptyList()
        val checks=mutableListOf<CrossingCheck>()
        for (branch in paid) {
            val route=branch.prefix
            val at=route.indexOfFirst { it.x==p.x && it.z==p.z }
            if (at<0) continue
            val replacement=joins[branch.owner]
            val end=replacement?.lastOrNull()
            val oldEnd=if (end==null) -1 else route.indexOf(end)
            val walk=if (oldEnd<0 || at>oldEnd) route else requireNotNull(replacement)+route.drop(oldEnd+1).take(1)
            for (i in maxOf(0,at-1) until minOf(route.lastIndex,at+1)) {
                val old=listOf(route[i],route[i+1])
                val mapped=walk.indexOfFirst { it.x==route[i].x && it.z==route[i].z }
                val next=walk.getOrNull(mapped+1)
                if (mapped>=0 && next!=null && next.x==route[i+1].x && next.z==route[i+1].z) {
                    checks+=CrossingCheck(old,listOf(walk[mapped],next))
                } else checks+=CrossingCheck(old,walk)
            }
        }
        // A paid branch's exposed old shoulder may be the last remaining access. Retire only after its replacement is open.
        if (bank(p) && changes.any { paidFinish(it.position,it.before) }) {
            for (b in witness.branches) {
                val walk=joins[b.owner] ?: continue
                if (b.prefix.none { it.x==p.x && it.z==p.z } && b.prefix.any {
                        abs(it.x.toLong()-p.x)<=1L && abs(it.z.toLong()-p.z)<=1L } &&
                    walk.none { it.x==p.x && it.z==p.z }) checks+=CrossingCheck(emptyList(),walk)
            }
        }
        return checks.distinct()
    }

    fun fundedBefore(p: CivPosition, data: String): Boolean {
        if (p !in surfaces) return false
        retiredShoulders[p]?.let { return it == data }
        if (cover(data) || air(data) || data == "minecraft:coarse_dirt") return true
        if (data == "minecraft:dirt_path") return yard(p) || paidFinish(p, data)
        if (yard(p)) return CivsRoadMaintenance.forecourtSoil(data)
        return CivsRoadMaintenance.clearanceGround(data) || data == "minecraft:gravel" ||
            paidFinish(p, data)
    }

    fun admitted(city: Settlement, access: CivAutonomousAccess) = admissionConflict(city,access)==null
    fun admissionConflict(city: Settlement, access: CivAutonomousAccess): String? {
        val owners = witness.branches.associateBy { it.owner }
        for (branch in witness.branches) {
            val walk=joins[branch.owner] ?: continue
            val route=access.routes[branch.owner] ?: return "route ${branch.owner}"
            val join=route.indexOf(walk.last())
            if (join<0 || !publishable(walk+route.drop(join+1))) return "splice ${branch.owner}"
        }
        val crossRows = witness.crossings.orEmpty().associate { crossing ->
            crossing.owner to crossing.prefix.windowed(3).filter { run ->
                run.all { depth(it)==depth(run.first()) && it.y==run.first().y } &&
                    run.map(::lane).sorted()==(axis-1..axis+1).toList()
            }.mapTo(hashSetOf()) { run -> depth(run.first()) }
        }
        val protected = civsProtectedBuildings(city).toList()
        val bounds = protected.associateWith { CivsAutonomousGeometry.futureBounds(city, it) }
        for (floor in surfaces.keys + retainedTerminalFloors) {
            if (!city.contains(floor.x, floor.z) || access.reservedFortificationPorts.any { CivsAutonomousGeometry.horizontalContains(it, floor) } ||
                access.infrastructure.any { piece -> piece.transit.any { it.x == floor.x && it.z == floor.z } }) return "parcel $floor"
            for (other in protected) {
                if (CivsWorksiteLayouts.isField(other, floor.x, floor.z) ||
                    other.control?.let { it.x == floor.x && it.z == floor.z } == true) return "field/control $floor ${other.id}"
                if (other.id == hall.id && yard(floor)) continue
                if (CivsAutonomousGeometry.horizontalContains(bounds.getValue(other), floor)) return "bounds $floor ${other.id}"
                if (other.id == hall.id) continue
                if (other.transit.orEmpty().any { it.x == floor.x && it.z == floor.z }) return "transit $floor ${other.id}"
                if (!other.type.isInfrastructure && CivsAutonomousGeometry.approach(other).any { it.x == floor.x && it.z == floor.z })
                    return "entrance $floor ${other.id}"
                val route = access.routes[other.id] ?: CivsSettlementPlans.current(city)?.routes?.get(other.id.toString()) ?: continue
                val crossing = witness.crossings.orEmpty().firstOrNull { it.owner == other.id }
                val crossingRows = crossRows[other.id].orEmpty()
                val tail = owners[other.id]?.prefix?.size ?: 0
                // Infrastructure may share the founding approach. Its authored transit and independent re-entries remain reserved.
                val sharedStart = if (other.type.isInfrastructure && route.firstOrNull() == hall.entrance)
                    route.takeWhile { depth(it) in 0..DEPTH && abs(lane(it)) <= SIDE }.size else tail
                if (route.asSequence().drop(sharedStart).any { p ->
                        if (abs(p.x.toLong()-floor.x)>1L || abs(p.z.toLong()-floor.z)>1L) false
                        else if (floor in retainedTerminalFloors)
                            crossing == null || floor !in outerStreets[other.id].orEmpty() || p !in crossing.prefix
                        else if (crossing == null || crossing.owner in outerStreets || p !in crossing.prefix) true
                        else if (p.x==floor.x && p.z==floor.z) {
                            val onPlane=surfaces[floor]=="minecraft:dirt_path" && p.y==floor.y+1
                            !onPlane || depth(p) !in crossingRows && joins.values.none { walk -> p in walk }
                        } else {
                            val oldFloor=paidProfiles[crossing.owner]?.get(civsTerrainKey(floor.x,floor.z))
                            val levelShoulder=oldFloor==floor && surfaces[floor] in setOf("minecraft:dirt_path",CivsEntrancePath.GRASS) &&
                                crossingRows.any { abs(it-depth(floor))<=1 }
                            !levelShoulder && depth(p) !in crossingRows && joins.values.none { walk -> p in walk }
                        }
                    }) return "route $floor ${other.id}"
            }
        }
        return null
    }

    /** Keep connected branches at the level street. A side exit at the crest stays accessible from its own landing. */
    private fun publishable(route: List<CivPosition>) = route.size<=CivsAutonomousTerraform.MAXIMUM_ROUTE_LENGTH &&
        route.distinctBy { civsTerrainKey(it.x,it.z) }.size==route.size

    fun travel(route: List<CivPosition>): List<CivPosition> {
        if (route.firstOrNull() != hall.entrance || route.size < 2) return route
        val branch = witness.branches.firstOrNull { b ->
            route.size >= b.prefix.size && b.prefix.indices.all { i -> route[i] == b.prefix[i] } } ?: return route
        val local = joins[branch.owner] ?: return route
        val join = route.indexOfFirst { p -> p == local.last() }
        if (join < 0) return route
        val projected = local + route.drop(join + 1)
        if (!publishable(projected)) return route
        return projected
    }

    companion object {
        private const val DEPTH = 10
        private const val SIDE = 4
        private val hash = Regex("[0-9a-f]{64}")
        fun air(data: String) = data in setOf("minecraft:air", "minecraft:void_air", "minecraft:cave_air")
        fun cover(data: String) = data.substringBefore('[') in setOf("minecraft:short_grass", "minecraft:fern", "minecraft:tall_grass",
            "minecraft:large_fern", "minecraft:poppy", "minecraft:dandelion", "minecraft:cornflower", "minecraft:azure_bluet",
            "minecraft:oxeye_daisy", "minecraft:leaf_litter", "minecraft:snow", "minecraft:wildflowers")

        fun capture(city: Settlement, plans: Map<UUID, CivPlacementPlan>, routes: Map<UUID, List<CivPosition>>): CivHallApproach? {
            val hall = city.buildings.singleOrNull { it.phase == BuildingPhase.ACTIVE && it.type == BuildingType.TOWN_HALL } ?: return null
            val founding = plans[hall.id] ?: return null
            val first = founding.building
            val sha = founding.capturedSha256 ?: return null
            val historic = CivsWorksiteLayouts.site(first)?.get("schematicSha256")?.asString
            // Immutable private worksite rows bind their own historical level, not the current upgrade's bytes.
            if (historic != null && historic != sha) return null
            if (founding.id != hall.id || founding.cityId != city.id || founding.worldId != city.worldId || founding.adminPlacement ||
                first.id != hall.id || first.type != hall.type || first.anchor != hall.anchor || first.rotation != hall.rotation ||
                first.bounds != hall.bounds || first.entrance != hall.entrance || first.level > hall.level || !hash.matches(sha)) return null
            if (first.schematicId != hall.schematicId) {
                val site = CivsWorksiteLayouts.site(hall) ?: return null
                if (site.getAsJsonObject("upgradeSchematics")?.get(first.level.toString())?.asString != first.schematicId) return null
            }
            val front = CivsAutonomousGeometry.frontage(hall)
            val branches = routes.mapNotNull { (id, route) ->
                val source = city.building(id) ?: return@mapNotNull null
                val plan = plans[id] ?: return@mapNotNull null
                if (source.type.isInfrastructure || id == hall.id || route.firstOrNull() != hall.entrance || plan.adminPlacement ||
                    plan.id != CivsWarehouseRefit.retainedPlanId(source) || plan.building.id != id || !hash.matches(plan.capturedSha256.orEmpty()))
                    return@mapNotNull null
                val prefix = route.takeWhile { p ->
                    val x = p.x - hall.entrance.x
                    val z = p.z - hall.entrance.z
                    x * front.x + z * front.z in 0..DEPTH && abs(x * front.z - z * front.x) <= SIDE
                }
                if (prefix.size < 2) return@mapNotNull null
                val digest = CivsGateRefit.sha256(route.joinToString("|") { "${it.x},${it.y},${it.z}" }.toByteArray(Charsets.US_ASCII))
                CivHallBranch(id, plan.id, digest, prefix.toList())
            }
            if (branches.isEmpty()) return null
            // A spatially independent wall path may cross the foot of the Hall. Preserve its existing
            // flat centreline and signed height. Its own structure, gate ports and ROAD transit stay excluded.
            val ordinary = CivHallApproach(CivPerimeterCapture(founding.id, sha), branches)
            val grade by lazy(LazyThreadSafetyMode.NONE) { from(city,hall,ordinary) }
            val crossings = routes.mapNotNull { (id, route) ->
                val source = city.building(id) ?: return@mapNotNull null
                val plan = plans[id] ?: return@mapNotNull null
                if (source.type != BuildingType.WALL || route.firstOrNull() == hall.entrance || plan.adminPlacement ||
                    plan.id != CivsWarehouseRefit.retainedPlanId(source) || !hash.matches(plan.capturedSha256.orEmpty())) return@mapNotNull null
                val hit = route.indices.filter { index ->
                    val p = route[index]
                    val x = p.x - hall.entrance.x
                    val z = p.z - hall.entrance.z
                    x * front.x + z * front.z in 1..DEPTH && abs(x * front.z - z * front.x) <= SIDE
                }
                if (hit.size < 3 || hit.last() - hit.first() + 1 != hit.size) return@mapNotNull null
                val segment=route.subList(hit.first(),hit.last()+1)
                val axis=CivsWorksiteLayouts.hallRows(hall)?.first ?: return@mapNotNull null
                val flatCross = segment.groupBy { (it.x-hall.entrance.x)*front.x+(it.z-hall.entrance.z)*front.z }.values.any { row ->
                    (axis-1..axis+1).all { lane -> row.any { (it.x-hall.entrance.x)*front.z-(it.z-hall.entrance.z)*front.x==lane } } &&
                        row.map { it.y }.distinct().size==1
                }
                val layout = grade ?: return@mapNotNull null
                if (layout.bordersTerminal(segment)) {
                    if (layout.terminalShoulder(segment).isEmpty()) return@mapNotNull null
                } else if (!flatCross) return@mapNotNull null
                CivHallBranch(id, plan.id, CivsGateRefit.sha256(route.joinToString("|") { "${it.x},${it.y},${it.z}" }
                    .toByteArray(Charsets.US_ASCII)), route.subList(hit.first(), hit.last() + 1).toList())
            }
            return ordinary.copy(crossings = crossings.takeIf { it.isNotEmpty() })
        }

        fun from(city: Settlement, hall: CivBuilding, witness: CivHallApproach): CivsTownHallApproach? {
            if (hall.type != BuildingType.TOWN_HALL || hall.phase != BuildingPhase.ACTIVE ||
                witness.founding.id != hall.id || !witness.founding.valid() ||
                witness.branches.size !in 1..CivsRules.MAX_SITES || witness.branches.distinctBy { it.owner }.size != witness.branches.size) return null
            val (axis, rows) = CivsWorksiteLayouts.hallRows(hall) ?: return null
            val front = CivsAutonomousGeometry.frontage(hall)
            val byDepth = (0..DEPTH).map { mutableListOf<Pair<Int, Int>>() }
            for (branch in witness.branches) {
                val source = city.building(branch.owner) ?: return null
                val prefix = branch.prefix
                if (source.type.isInfrastructure || source.id == hall.id || source.phase != BuildingPhase.ACTIVE ||
                    branch.plan != CivsWarehouseRefit.retainedPlanId(source) || !hash.matches(branch.routeSha256) ||
                    prefix.size !in 2..((DEPTH + 1) * (SIDE * 2 + 1)) || prefix.first() != hall.entrance ||
                    prefix.distinctBy { civsTerrainKey(it.x, it.z) }.size != prefix.size ||
                    prefix.zipWithNext().any { (a, b) -> abs(a.x.toLong() - b.x) + abs(a.z.toLong() - b.z) != 1L || abs(a.y.toLong() - b.y) > 1L }) return null
                for (p in prefix) {
                    val x = p.x - hall.entrance.x
                    val z = p.z - hall.entrance.z
                    val d = x * front.x + z * front.z
                    val s = x * front.z - z * front.x
                    if (!city.contains(p.x, p.z) || p.y !in -4093..4094 || d !in 0..DEPTH || abs(s) > SIDE) return null
                    byDepth[d].add(abs(s) to p.y)
                }
            }
            val grades = mutableListOf(hall.entrance.y)
            var sign = 0
            for (d in 1..DEPTH) {
                val points = byDepth[d]
                val nearest = points.minOfOrNull { it.first } ?: break
                val height = points.asSequence().filter { it.first == nearest }.map { it.second }.distinct().toList().singleOrNull() ?: break
                val change = height - grades.last()
                if (abs(change) > 1 || change != 0 && sign != 0 && change != sign) break
                grades.add(height)
                if (change != 0) sign = change
            }
            if (grades.size < 2) return null
            val lastStep = (1..grades.lastIndex).lastOrNull { grades[it] != grades[it - 1] } ?: 0
            if (lastStep == grades.lastIndex) return null
            val end = maxOf(minOf(3, grades.lastIndex), lastStep + 1)
            val crossings = witness.crossings.orEmpty()
            if (crossings.size > CivsRules.MAX_SITES || crossings.distinctBy { it.owner }.size != crossings.size) return null
            for (crossing in crossings) {
                val source = city.building(crossing.owner) ?: return null
                val points = crossing.prefix
                if (source.type != BuildingType.WALL || source.phase != BuildingPhase.ACTIVE ||
                    crossing.plan != CivsWarehouseRefit.retainedPlanId(source) || !hash.matches(crossing.routeSha256) ||
                    points.size !in 3..DEPTH * (2 * SIDE + 1) ||
                    points.distinctBy { civsTerrainKey(it.x, it.z) }.size != points.size ||
                    points.zipWithNext().any { (a,b) -> abs(a.x.toLong()-b.x)+abs(a.z.toLong()-b.z)!=1L || abs(a.y.toLong()-b.y)>1L }) return null
                val local = points.map { p ->
                    val x = p.x - hall.entrance.x
                    val z = p.z - hall.entrance.z
                    Triple(x * front.x + z * front.z, x * front.z - z * front.x, p.y)
                }
                if (local.any { (d,s,y) -> d !in 1..DEPTH || abs(s)>SIDE || y !in -4093..4094 }) return null
                val rows = local.filter { (_,s,_) -> s in axis-1..axis+1 }.groupBy { it.first }
                for ((d, row) in rows) {
                    if (d > end) continue
                    val y = row.map { it.third }.distinct().singleOrNull() ?: return null
                    if (row.map { it.second }.distinct().size != 3 || grades[d] != y) return null
                    if (grades[d-1] != y) {
                        if (d < 3 || grades[d-2] != grades[d-1]) return null
                        grades[d-1] = y
                    }
                }
            }
            return CivsTownHallApproach(hall, witness, axis, rows, grades.take(end + 1), city.style)
                .takeUnless { layout -> layout.outerStreets.values.any { it.isEmpty() } }
        }
    }
}
