package ai.starlake.quack.ondemand.state

import java.time.{Duration, Instant}
import scala.collection.mutable

final class InMemoryFleetServerStore(clock: () => Instant = () => Instant.now())
    extends FleetServerStore:
  private val rows = mutable.LinkedHashMap.empty[String, FleetServerRow]

  private def withSilent(r: FleetServerRow): FleetServerRow =
    r.copy(
      silentSeconds = Duration.between(r.lastHeartbeatAt, clock()).getSeconds,
      claimAgeSeconds = r.claimedAt.map(c => Duration.between(c, clock()).getSeconds)
    )

  /** Test hook: move a server's heartbeat (and join) `seconds` into the past. */
  def backdate(name: String, seconds: Long): Unit = synchronized {
    rows
      .get(name)
      .foreach(r =>
        rows.put(
          name,
          r.copy(
            lastHeartbeatAt = r.lastHeartbeatAt.minusSeconds(seconds),
            joinedAt = r.joinedAt.minusSeconds(seconds)
          )
        )
      )
  }

  /** Test hook: move a server's claimed_at `seconds` into the past. */
  def backdateClaim(name: String, seconds: Long): Unit = synchronized {
    rows
      .get(name)
      .foreach(r => rows.put(name, r.copy(claimedAt = r.claimedAt.map(_.minusSeconds(seconds)))))
  }

  /** Test hook: write an assignment onto a server whatever its state (the claim guards bypassed),
    * to pin what the heartbeat reply does with a row that should never hold one.
    */
  def forceAssignment(name: String, a: FleetAssignment): Unit = synchronized {
    rows
      .get(name)
      .foreach(r =>
        rows.put(
          name,
          r.copy(
            assignedNodeId = Some(a.nodeId),
            assignment = Some(a),
            assignmentEpoch = a.epoch,
            claimedAt = Some(clock())
          )
        )
      )
  }

  /** Test hook: clear a server's source binding (the 0042 upgrade leaves approved rows unbound). */
  def unbind(name: String): Unit = synchronized {
    rows.get(name).foreach(r => rows.put(name, r.copy(approvedSource = None)))
  }

  def recordHeartbeat(hb: Heartbeat): HeartbeatOutcome = synchronized {
    val now = clock()
    rows.get(hb.name) match
      case None =>
        rows.put(
          hb.name,
          FleetServerRow(
            hb.name,
            hb.advertiseHost,
            hb.nodePort,
            now,
            unschedulable = false,
            None,
            None,
            0L,
            None,
            now,
            0L,
            None,
            hb.agentVersion,
            hb.os,
            hb.duckdbVersion,
            hb.cpus,
            hb.memoryBytes,
            FleetServerStore.effectiveState(hb.node, 0L, None),
            hb.node.error,
            hb.node.pid,
            hb.node.startedAt,
            approved = hb.autoApprove,
            approvedAt = Option.when(hb.autoApprove)(now),
            approvedBy = Option.when(hb.autoApprove)("auto"),
            sourceAddr = hb.sourceAddr,
            approvedSource = if hb.autoApprove then hb.sourceAddr else None
          )
        )
        HeartbeatOutcome.Joined
      case Some(r)
          if (!r.unschedulable || r.assignedNodeId.isDefined) &&
            (r.advertiseHost != hb.advertiseHost || r.nodePort != hb.nodePort) =>
        HeartbeatOutcome.AddressChangeRefused
      case Some(r)
          if sourceMoved(
            r,
            hb
          ) && !hb.autoApprove && !(r.unschedulable && r.assignedNodeId.isEmpty) =>
        HeartbeatOutcome.SourceChangeRefused
      case Some(r)
          if unbound(r) && !trustedBind(hb) && !(r.unschedulable && r.assignedNodeId.isEmpty) =>
        // The address guard above let only an unchanged address through (or a drained,
        // unassigned re-address, which the last guard excludes too).
        HeartbeatOutcome.ApprovalUnbound
      case Some(r) =>
        // Only a drained, unassigned server gets here with another address (the guard above
        // refuses the rest); a re-address resets approval so the new address is judged again.
        // So does a source move outside the list (drained and unassigned too); a move inside the
        // list keeps the approval and rebinds it to the new source. An unbound approval keeps
        // its approval (and binds) only from a known source inside the list; a drained,
        // unassigned one heartbeating from elsewhere is reset.
        val readdressed   = r.advertiseHost != hb.advertiseHost || r.nodePort != hb.nodePort
        val moved         = sourceMoved(r, hb)
        val keepsApproval = r.approved && !readdressed && !(moved && !hb.autoApprove) &&
          !(unbound(r) && !trustedBind(hb))
        rows.put(
          hb.name,
          r.copy(
            advertiseHost = hb.advertiseHost,
            nodePort = hb.nodePort,
            lastHeartbeatAt = now,
            agentVersion = hb.agentVersion,
            os = hb.os,
            duckdbVersion = hb.duckdbVersion,
            cpus = hb.cpus,
            memoryBytes = hb.memoryBytes,
            nodeState =
              FleetServerStore.effectiveState(hb.node, r.assignmentEpoch, r.assignedNodeId),
            nodeError = hb.node.error,
            nodePid = hb.node.pid,
            nodeStartedAt = hb.node.startedAt,
            approved = keepsApproval || hb.autoApprove,
            approvedAt = if keepsApproval then r.approvedAt else Option.when(hb.autoApprove)(now),
            approvedBy =
              if keepsApproval then r.approvedBy else Option.when(hb.autoApprove)("auto"),
            sourceAddr = hb.sourceAddr,
            approvedSource =
              if keepsApproval then
                if moved then hb.sourceAddr else r.approvedSource.orElse(hb.sourceAddr)
              else if hb.autoApprove then hb.sourceAddr
              else None
          )
        )
        HeartbeatOutcome.Updated
  }

  /** An approved row bound to no source (the 0042 upgrade backfill). */
  private def unbound(r: FleetServerRow): Boolean = r.approved && r.approvedSource.isEmpty

  /** A heartbeat allowed to bind an unbound approval: a known source inside the list. The isDefined
    * half is defense in depth; Heartbeat already refuses autoApprove without a source.
    */
  private def trustedBind(hb: Heartbeat): Boolean = hb.sourceAddr.isDefined && hb.autoApprove

  /** An approved row bound to a source other than this heartbeat's (an unknown one included). */
  private def sourceMoved(r: FleetServerRow, hb: Heartbeat): Boolean =
    r.approved && r.approvedSource.isDefined && r.approvedSource != hb.sourceAddr

  def claim(
      a: FleetAssignment,
      reachableWithinSec: Int,
      requiredMemoryBytes: Option[Long]
  ): Either[ClaimMiss, FleetServerRow] =
    synchronized {
      val now  = clock()
      val free = rows.values
        .filter(r =>
          r.approved && r.assignedNodeId.isEmpty && !r.unschedulable &&
            Duration.between(r.lastHeartbeatAt, now).getSeconds < reachableWithinSec
        )
        .toList
      val fit =
        free.filter(r => requiredMemoryBytes.forall(need => r.memoryBytes.forall(_ >= need)))
      fit.sortBy(_.joinedAt).headOption match
        case None if free.isEmpty => Left(ClaimMiss.NoneFree)
        case None                 => Left(ClaimMiss.NoneFits)
        case Some(_) if rows.values.exists(_.assignedNodeId.contains(a.nodeId)) =>
          // Mirrors the UNIQUE constraint on qodstate_fleet_server.assigned_node_id.
          throw new java.sql.SQLException(
            s"duplicate key value violates unique constraint (assigned_node_id)=(${a.nodeId})",
            "23505"
          )
        case Some(r) =>
          val epoch   = r.assignmentEpoch + 1
          val updated = r.copy(
            assignedNodeId = Some(a.nodeId),
            assignment = Some(a.copy(epoch = epoch, port = r.nodePort)),
            assignmentEpoch = epoch,
            claimedAt = Some(now)
          )
          rows.put(r.name, updated); Right(withSilent(updated))
    }

  def claimReplacing(
      a: FleetAssignment,
      reachableWithinSec: Int,
      requiredMemoryBytes: Option[Long]
  ): Either[ClaimMiss, FleetServerRow] =
    synchronized {
      val before = rows.clone()
      release(a.nodeId)
      val result = claim(a, reachableWithinSec, requiredMemoryBytes)
      if result.isLeft then
        // Roll back: the old holder keeps its assignment when no replacement exists.
        rows.clear(); rows ++= before
      result
    }

  def setAssignment(name: String, a: FleetAssignment): Unit = synchronized {
    rows.get(name).foreach(r => rows.put(name, r.copy(assignment = Some(a))))
  }

  def release(nodeId: String): Option[String] = synchronized {
    rows.values.find(_.assignedNodeId.contains(nodeId)).map { r =>
      rows.put(
        r.name,
        r.copy(
          assignedNodeId = None,
          assignment = None,
          assignmentEpoch = r.assignmentEpoch + 1,
          claimedAt = None
        )
      )
      r.name
    }
  }

  def get(name: String): Option[FleetServerRow] = synchronized(rows.get(name).map(withSilent))
  def list(): List[FleetServerRow]              = synchronized(rows.values.toList.map(withSilent))
  def byNodeId(nodeId: String): Option[FleetServerRow] =
    synchronized(rows.values.find(_.assignedNodeId.contains(nodeId)).map(withSilent))
  def setUnschedulable(name: String, value: Boolean): Boolean = synchronized {
    rows.get(name).map(r => rows.put(name, r.copy(unschedulable = value))).isDefined
  }
  def approve(name: String, by: String): ApproveResult = synchronized {
    rows.get(name) match
      case None                            => ApproveResult.NotFound
      case Some(r) if r.approved           => ApproveResult.AlreadyApproved
      case Some(r) if r.sourceAddr.isEmpty => ApproveResult.SourceUnknown
      case Some(r)                         =>
        rows.put(
          name,
          r.copy(
            approved = true,
            approvedAt = Some(clock()),
            approvedBy = Some(by),
            approvedSource = r.sourceAddr
          )
        )
        ApproveResult.Approved
  }
  def delete(name: String): Boolean = synchronized(rows.remove(name).isDefined)
